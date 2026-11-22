// GoldLeaf ADR-017 packed baseline OpenCL C99 kernel (FP64 VANILLA_EXACT)
#pragma OPENCL EXTENSION cl_khr_fp64 : enable

__kernel void goldleaf_density_volume_batch_fp64(
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

    // Deterministic height-gradient baseline density (positive below sea level, negative above)
    const double gradient = (64.0 - (double) worldY) * 0.015625;
    const double wave = 0.125 * sin((double) worldX * 0.03125) * cos((double) worldZ * 0.03125);
    outDensity[gid] = (float) (gradient + wave);
}
