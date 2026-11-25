package org.ghostcapture

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect

@UnstableApi
internal class HudFillEffect(private val regions: List<Region>) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException("HUD fill requires SDR input.")
        return object : BaseGlShaderProgram(false, 1) {
            private val program = GlProgram(VERTEX, fragment(regions.size))
            private var width = 1
            private var height = 1

            override fun configure(inputWidth: Int, inputHeight: Int): Size {
                width = inputWidth
                height = inputHeight
                return Size(width, height)
            }

            override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
                try {
                    program.use()
                    program.setSamplerTexIdUniform("uTex", inputTexId, 0)
                    program.setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), 4)
                    program.setFloatsUniform("uPixel", floatArrayOf(1f / width, 1f / height))
                    regions.forEachIndexed { index, rect ->
                        program.setFloatsUniform("uRect$index", floatArrayOf(rect.left, 1f - rect.bottom, rect.right, 1f - rect.top))
                    }
                    program.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GlUtil.checkGlError()
                } catch (error: GlUtil.GlException) {
                    throw VideoFrameProcessingException(error)
                }
            }

            override fun release() {
                super.release()
                program.delete()
            }
        }
    }

    private companion object {
        const val VERTEX = """
            attribute vec4 aPosition;
            varying vec2 vUv;
            void main() {
                gl_Position = aPosition;
                vUv = aPosition.xy * 0.5 + 0.5;
            }
        """

        fun fragment(count: Int): String {
            val uniforms = (0 until count).joinToString("\n") { "uniform vec4 uRect$it;" }
            val checks = (0 until count).joinToString("\n") { "if (inside(p, uRect$it)) return true;" }
            val fills = (0 until count).joinToString("\n") { "if (inside(vUv, uRect$it)) { gl_FragColor = fill(uRect$it); return; }" }
            return """
                precision highp float;
                varying vec2 vUv;
                uniform sampler2D uTex;
                uniform vec2 uPixel;
                $uniforms
                bool inside(vec2 p, vec4 r) {
                    return all(greaterThanEqual(p, r.xy)) && all(lessThanEqual(p, r.zw));
                }
                bool masked(vec2 p) {
                    $checks
                    return false;
                }
                void addSample(vec2 p, float distance, inout vec4 color, inout float weight) {
                    if (all(greaterThanEqual(p, vec2(0.0))) && all(lessThanEqual(p, vec2(1.0))) && !masked(p)) {
                        float w = 1.0 / max(1.0, distance);
                        color += texture2D(uTex, p) * w;
                        weight += w;
                    }
                }
                vec4 fill(vec4 r) {
                    vec4 color = vec4(0.0);
                    float weight = 0.0;
                    for (int i = 2; i <= 8; i += 2) {
                        vec2 delta = float(i) * uPixel;
                        float x = r.x - delta.x;
                        float X = r.z + delta.x;
                        float y = r.y - delta.y;
                        float Y = r.w + delta.y;
                        addSample(vec2(x, vUv.y), (vUv.x - x) / uPixel.x, color, weight);
                        addSample(vec2(X, vUv.y), (X - vUv.x) / uPixel.x, color, weight);
                        addSample(vec2(vUv.x, y), (vUv.y - y) / uPixel.y, color, weight);
                        addSample(vec2(vUv.x, Y), (Y - vUv.y) / uPixel.y, color, weight);
                    }
                    if (weight > 0.0) return color / weight;
                    return texture2D(uTex, vUv);
                }
                void main() {
                    $fills
                    gl_FragColor = texture2D(uTex, vUv);
                }
            """
        }
    }
}
