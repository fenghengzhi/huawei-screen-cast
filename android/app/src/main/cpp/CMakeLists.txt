cmake_minimum_required(VERSION 3.22.1)
project(huaweicast_aaceld LANGUAGES C CXX)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
get_filename_component(FDK_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/fdk-aac" ABSOLUTE)
add_subdirectory("${FDK_SOURCE_DIR}" fdk)

# Upstream's decoder has two platform-private SafetyNet log writes. Its portable
# path retains the bounds checks without requiring Android's non-NDK log header.
set_source_files_properties("${FDK_SOURCE_DIR}/libSBRdec/src/lpp_tran.cpp"
    DIRECTORY "${FDK_SOURCE_DIR}" PROPERTIES COMPILE_OPTIONS "-U__ANDROID__")

add_library(huaweicast_aaceld SHARED native_aac_eld_encoder.cpp)
target_compile_features(huaweicast_aaceld PRIVATE cxx_std_17)
target_compile_options(huaweicast_aaceld PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(huaweicast_aaceld PRIVATE FDK-AAC::fdk-aac)
target_link_options(huaweicast_aaceld PRIVATE -Wl,--exclude-libs,ALL)
