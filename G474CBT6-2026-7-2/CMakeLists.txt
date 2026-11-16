cmake_minimum_required(VERSION 3.22)

# Setup compiler settings
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS ON)

# Define the build type
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE "Debug")
endif()

# Set the project name
set(CMAKE_PROJECT_NAME G474CBT6-2026-7-2)

# Enable compile command to ease indexing with e.g. clangd
set(CMAKE_EXPORT_COMPILE_COMMANDS TRUE)

# Core project settings
project(${CMAKE_PROJECT_NAME})
message("Build type: " ${CMAKE_BUILD_TYPE})

# Enable CMake support for ASM and C languages
enable_language(C ASM)

# 【修改点 1】让主 executable 保持干净，不直接在这里硬编码添加 User/DWT.c
add_executable(${CMAKE_PROJECT_NAME}
        User/bsp_dwt.h
        User/bsp_dwt.c
        User/task.h
        User/task.c
        User/vofa.h
        User/vofa.c
        User/ws2812.c
        User/loop.h
        User/loop.c
)

# Add STM32CubeMX generated sources
add_subdirectory(cmake/stm32cubemx)

# Link directories setup
target_link_directories(${CMAKE_PROJECT_NAME} PRIVATE
        # Add user defined library search paths
)

# 【修改点 2】如果你发现注释掉上面后提示找不到 DWT 函数，
# 说明 CubeMX 没自动扫到它，这时候才需要在这里显式通过 target_sources 追加：
# target_sources(${CMAKE_PROJECT_NAME} PRIVATE
#     User/DWT.c
# )

# 【修改点 3】必须显式添加 User 文件夹的头文件搜索路径，否则 main.c 编到 #include "DWT.h" 时会报找不到头文件
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        User
)

# Add project symbols (macros)
target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        # Add user defined symbols
)

# Remove wrong libob.a library dependency when using cpp files
list(REMOVE_ITEM CMAKE_C_IMPLICIT_LINK_LIBRARIES ob)

# Add linked libraries
target_link_libraries(${CMAKE_PROJECT_NAME}
        stm32cubemx
        # Add user defined libraries
)