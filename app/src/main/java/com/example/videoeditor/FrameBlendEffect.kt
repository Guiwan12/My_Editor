package com.example.videoeditor

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * EXPERIMENTAL frame blend: mixes each frame with the previous one on the GPU to
 * soften the stutter of slow motion. This is NOT optical-flow interpolation (it
 * can't invent true in-between frames) — it's a cheap cross-fade, so fast motion
 * will ghost. Off by default; meant for testing on a real device.
 *
 * Mechanics (mirrors Media3's AlphaScaleShaderProgram pattern):
 *  - a blend program samples the current frame + a saved "previous" texture
 *  - a copy program writes the current frame into that previous texture for the
 *    next call, via its own FBO, restoring the output framebuffer afterward.
 */
@UnstableApi
class FrameBlendEffect(private val blendAmount: Float = 0.5f) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        FrameBlendShaderProgram(blendAmount)
}

@UnstableApi
private class FrameBlendShaderProgram(
    private val blendAmount: Float,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1) {

    private val blendProgram: GlProgram
    private val copyProgram: GlProgram
    private var prevTexId = C.INDEX_UNSET
    private var prevFboId = C.INDEX_UNSET
    private var width = 0
    private var height = 0
    private var hasPrev = false

    init {
        try {
            blendProgram = GlProgram(VERTEX_SHADER, BLEND_FRAGMENT_SHADER)
            copyProgram = GlProgram(VERTEX_SHADER, COPY_FRAGMENT_SHADER)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        for (p in listOf(blendProgram, copyProgram)) {
            p.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (inputWidth != width || inputHeight != height) {
            try {
                if (prevTexId != C.INDEX_UNSET) GlUtil.deleteTexture(prevTexId)
                if (prevFboId != C.INDEX_UNSET) GLES20.glDeleteFramebuffers(1, intArrayOf(prevFboId), 0)
                prevTexId = GlUtil.createTexture(inputWidth, inputHeight, /* useHighPrecisionColorComponents= */ false)
                prevFboId = GlUtil.createFboForTexture(prevTexId)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
            width = inputWidth
            height = inputHeight
            hasPrev = false
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val outputFbo = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, outputFbo, 0)

            // 1) Blend current + previous into the output (already focused by caller).
            blendProgram.use()
            blendProgram.setSamplerTexIdUniform("uTexSampler0", inputTexId, /* texUnitIndex= */ 0)
            blendProgram.setSamplerTexIdUniform(
                "uTexSampler1", if (hasPrev) prevTexId else inputTexId, /* texUnitIndex= */ 1
            )
            blendProgram.setFloatUniform("uBlend", if (hasPrev) blendAmount else 0f)
            blendProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)

            // 2) Save the current frame into prevTex for the next call.
            GlUtil.focusFramebufferUsingCurrentContext(prevFboId, width, height)
            copyProgram.use()
            copyProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            copyProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
            hasPrev = true

            // 3) Restore the output framebuffer.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo[0])
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            if (prevTexId != C.INDEX_UNSET) GlUtil.deleteTexture(prevTexId)
        } catch (e: GlUtil.GlException) {
            // best-effort cleanup
        }
        if (prevFboId != C.INDEX_UNSET) GLES20.glDeleteFramebuffers(1, intArrayOf(prevFboId), 0)
    }

    private companion object {
        // #version must be the very first characters; matches Media3's own shaders.
        const val VERTEX_SHADER =
            "#version 100\n" +
                "attribute vec4 aFramePosition;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "  gl_Position = aFramePosition;\n" +
                "  vTexCoord = aFramePosition.xy * 0.5 + 0.5;\n" +
                "}\n"

        const val BLEND_FRAGMENT_SHADER =
            "#version 100\n" +
                "precision mediump float;\n" +
                "uniform sampler2D uTexSampler0;\n" +
                "uniform sampler2D uTexSampler1;\n" +
                "uniform float uBlend;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "  vec4 c0 = texture2D(uTexSampler0, vTexCoord);\n" +
                "  vec4 c1 = texture2D(uTexSampler1, vTexCoord);\n" +
                "  gl_FragColor = mix(c0, c1, uBlend);\n" +
                "}\n"

        const val COPY_FRAGMENT_SHADER =
            "#version 100\n" +
                "precision mediump float;\n" +
                "uniform sampler2D uTexSampler;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(uTexSampler, vTexCoord);\n" +
                "}\n"
    }
}
