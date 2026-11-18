cmake_minimum_required(VERSION 3.24)
project(CmiParticleDxr LANGUAGES CXX RC)
find_package(JNI REQUIRED)
find_program(DXC NAMES dxc REQUIRED HINTS "$ENV{WindowsSdkDir}/bin/$ENV{WindowsSDKVersion}/x64")
set(CMAKE_CXX_STANDARD 17)
set(SHADERS applyControls advanceStorm prepareInstances queryMotion prepareFeedback finishDome
    clearFeathers compactFeathers publishPositions captureColumns publishSnapshot sealTick)
set(STORM_MATH_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../resources/assets/createmanaindustry/shaders/particles/chunks")
file(GLOB SHADER_INCLUDES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/*.hlsli")
set(DXIL_OUTPUTS)
set(DXIL_RESOURCES "")
set(RESOURCE_ID 101)
foreach(KERNEL IN LISTS SHADERS)
    set(BYTECODE "${CMAKE_CURRENT_BINARY_DIR}/${KERNEL}.dxil")
    add_custom_command(OUTPUT "${BYTECODE}"
        COMMAND "${DXC}" -HV 2021 -Gis -T cs_6_5 -E "${KERNEL}" -I "${STORM_MATH_DIR}"
            -Fo "${BYTECODE}" "${CMAKE_CURRENT_SOURCE_DIR}/StormDxr.hlsl"
        DEPENDS StormDxr.hlsl ${SHADER_INCLUDES} ../particle_common/ResourceLayout.h "${STORM_MATH_DIR}/storm_home.glsl"
        VERBATIM)
    list(APPEND DXIL_OUTPUTS "${BYTECODE}")
    string(APPEND DXIL_RESOURCES "${RESOURCE_ID} RCDATA \"${BYTECODE}\"\n")
    math(EXPR RESOURCE_ID "${RESOURCE_ID} + 1")
endforeach()
add_custom_target(storm_dxil DEPENDS ${DXIL_OUTPUTS})
configure_file(StormDxr.rc.in StormDxr.rc @ONLY)
set_source_files_properties("${CMAKE_CURRENT_BINARY_DIR}/StormDxr.rc" PROPERTIES
    OBJECT_DEPENDS "${DXIL_OUTPUTS};${CMAKE_CURRENT_SOURCE_DIR}/StormDxrVersion.h")
add_library(CmiParticleDxr SHARED StormDxrAdapter.cpp StormDxrControl.cpp StormDxrGpu.cpp
    "${CMAKE_CURRENT_BINARY_DIR}/StormDxr.rc")
target_include_directories(CmiParticleDxr PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" ${JNI_INCLUDE_DIRS})
target_compile_definitions(CmiParticleDxr PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX CMI_DXR_HOST)
target_link_libraries(CmiParticleDxr PRIVATE bcrypt d3d12 dxgi gdi32 opengl32 user32)
set_property(TARGET CmiParticleDxr PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
add_dependencies(CmiParticleDxr storm_dxil)
