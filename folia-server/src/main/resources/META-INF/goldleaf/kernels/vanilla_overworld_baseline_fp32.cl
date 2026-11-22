// GoldLeaf ADR-017 packed baseline OpenCL C99 kernel (FP32 FAST)
__kernel void goldleaf_density_volume_batch_fp32(
    __global const int* chunkCoords,
    __global float* outDensity,
    const int chunkCount,
    const int sizeX,
    const int sizeY,
    const int sizeZ,
    const int minY
) {
    const int gid = get_global_id(0);
    const int cellsPerChunk = sizeX * sizeY * sizeZ;
    const int totalCells = chunkCount * cellsPerChunk;
    if (gid >= totalCells) {
        return;
    }
    const int chunkIdx = gid / cellsPerChunk;
    const int localIdx = gid - chunkIdx * cellsPerChunk;
    const int y = localIdx / (sizeX * sizeZ);
    const int rem = localIdx - y * (sizeX * sizeZ);
    const int z = rem / sizeX;
    const int x = rem - z * sizeX;
    const int worldX = (chunkCoords[chunkIdx * 2] << 4) + x;
    const int worldY = minY + y;
    const int worldZ = (chunkCoords[chunkIdx * 2 + 1] << 4) + z;

    const float gradient = (64.0f - (float) worldY) * 0.015625f;
    const float wave = 0.125f * native_sin((float) worldX * 0.03125f) * native_cos((float) worldZ * 0.03125f);
    outDensity[gid] = gradient + wave;
}
