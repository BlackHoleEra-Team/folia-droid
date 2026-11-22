# android/app/src/main/cpp/CMakeLists.txt
# 只做一件事：把 libnode.so 和一层 JNI 编成 libfolia_node.so。
cmake_minimum_required(VERSION 3.22)
project(folia_node CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(LIBNODE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../libnode")
set(LIBNODE_SO "${LIBNODE_DIR}/bin/${ANDROID_ABI}/libnode.so")
if(NOT EXISTS "${LIBNODE_SO}")
    message(FATAL_ERROR "缺少 ${ANDROID_ABI} 的 libnode.so：${LIBNODE_SO}")
endif()

add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES IMPORTED_LOCATION "${LIBNODE_SO}")

add_library(folia_node SHARED folia_node.cpp)
target_include_directories(folia_node PRIVATE "${LIBNODE_DIR}/include/node")
target_link_libraries(folia_node libnode log)
