/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

import java.util.OptionalDouble;
import java.util.OptionalInt;

public class BaritoneRenderType extends RenderType {
    private final RenderPipeline renderPipeline;

    public BaritoneRenderType(String name, int bufferSize, boolean affectsCrumbling, boolean sortOnUpload, RenderPipeline renderPipeline) {
        super(name, bufferSize, affectsCrumbling, sortOnUpload, () -> {}, () -> {});
        this.renderPipeline = renderPipeline;
    }

    public static BaritoneRenderType create(String name, int bufferSize, RenderPipeline renderPipeline) {
        return new BaritoneRenderType(name, bufferSize, false, false, renderPipeline);
    }

    @Override
    public RenderPipeline getRenderPipeline() {
        return this.renderPipeline;
    }

    @Override
    public VertexFormat format() {
        return this.renderPipeline.getVertexFormat();
    }

    @Override
    public VertexFormat.Mode mode() {
        return this.renderPipeline.getVertexFormatMode();
    }

    @Override
    public void draw(final MeshData meshData) {
        RenderPipeline renderPipeline = this.getRenderPipeline();
        this.setupRenderState();

        try {
            GpuBuffer gpuBuffer = renderPipeline.getVertexFormat().uploadImmediateVertexBuffer(meshData.vertexBuffer());
            GpuBuffer gpuBuffer2;
            VertexFormat.IndexType indexType;
            if (meshData.indexBuffer() == null) {
                RenderSystem.AutoStorageIndexBuffer autoStorageIndexBuffer = RenderSystem.getSequentialBuffer(meshData.drawState().mode());
                gpuBuffer2 = autoStorageIndexBuffer.getBuffer(meshData.drawState().indexCount());
                indexType = autoStorageIndexBuffer.type();
            } else {
                gpuBuffer2 = renderPipeline.getVertexFormat().uploadImmediateIndexBuffer(meshData.indexBuffer());
                indexType = meshData.drawState().indexType();
            }

            RenderTarget renderTarget = getRenderTarget();

            try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                renderTarget.getColorTexture(),
                OptionalInt.empty(),
                renderTarget.useDepth ? renderTarget.getDepthTexture() : null,
                OptionalDouble.empty())
            ) {
                renderPass.setPipeline(renderPipeline);
                renderPass.setVertexBuffer(0, gpuBuffer);
                if (RenderSystem.SCISSOR_STATE.isEnabled()) {
                    renderPass.enableScissor(RenderSystem.SCISSOR_STATE);
                }

                for(int i = 0; i < 12; ++i) {
                    GpuTexture gpuTexture = RenderSystem.getShaderTexture(i);
                    if (gpuTexture != null) {
                        renderPass.bindSampler("Sampler" + i, gpuTexture);
                    }
                }

                renderPass.setIndexBuffer(gpuBuffer2, indexType);
                renderPass.drawIndexed(0, meshData.drawState().indexCount());
            }
        } catch (Throwable e) {
            try {
                meshData.close();
            } catch (Throwable e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }

        meshData.close();

        this.clearRenderState();
    }

    @Override
    public RenderTarget getRenderTarget() {
        return RenderStateShard.MAIN_TARGET.getRenderTarget();
    }
}
