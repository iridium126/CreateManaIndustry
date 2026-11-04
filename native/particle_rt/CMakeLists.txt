cmake_minimum_required(VERSION 3.24)
project(CmiParticleRt LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
find_package(JNI REQUIRED)
set(NVCC "${CRT_ROOT}/bin/nvcc.exe")
set(DEVICE_INCLUDES "-I${CUDA_ROOT}/include" "-I${CRT_ROOT}/include" "-I${OPTIX_ROOT}/include" "-I${CCCL_ROOT}/include" "-I${CMAKE_CURRENT_SOURCE_DIR}")
add_custom_command(OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/spatial.fatbin"
 COMMAND "${NVCC}" --fatbin -std=c++17 --generate-code=arch=compute_75,code=compute_75 --generate-code=arch=compute_89,code=sm_89 ${DEVICE_INCLUDES} "${CMAKE_CURRENT_SOURCE_DIR}/grid.cu" -o "${CMAKE_CURRENT_BINARY_DIR}/spatial.fatbin"
 DEPENDS grid.cu Spatial.h VERBATIM)
add_custom_command(OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/spatial.optixir"
 COMMAND "${NVCC}" --optix-ir -std=c++17 -arch=compute_75 ${DEVICE_INCLUDES} "${CMAKE_CURRENT_SOURCE_DIR}/rt.cu" -o "${CMAKE_CURRENT_BINARY_DIR}/spatial.optixir"
 DEPENDS rt.cu Spatial.h OcclusionProof.h VERBATIM)
add_custom_target(particle_device ALL DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/spatial.fatbin" "${CMAKE_CURRENT_BINARY_DIR}/spatial.optixir")
add_library(CmiParticleRt SHARED Spatial.cpp Interop.cpp)
target_include_directories(CmiParticleRt PRIVATE ${JNI_INCLUDE_DIRS} "${CUDA_ROOT}/include" "${CRT_ROOT}/include" "${OPTIX_ROOT}/include")
target_link_libraries(CmiParticleRt PRIVATE "${CUDA_ROOT}/lib/x64/cuda.lib" d3d12 dxgi opengl32)
target_compile_definitions(CmiParticleRt PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
add_dependencies(CmiParticleRt particle_device)
