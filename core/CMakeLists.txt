cmake_minimum_required(VERSION 3.20)
project(vantage_core CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(vantage_core STATIC src/core.cpp)
target_include_directories(vantage_core PUBLIC include)
if (MSVC)
  target_compile_options(vantage_core PRIVATE /W3 /utf-8)
endif()

add_executable(vantage-cli cli/main.cpp)
target_link_libraries(vantage-cli PRIVATE vantage_core)

add_executable(vantage-tests tests/test_core.cpp)
target_link_libraries(vantage-tests PRIVATE vantage_core)

if (ANDROID)
  add_library(vantage_jni SHARED jni/vantage_jni.cpp src/core.cpp)
  target_include_directories(vantage_jni PRIVATE include src)
  target_link_libraries(vantage_jni PRIVATE log)
endif()

enable_testing()
add_test(NAME core COMMAND vantage-tests)
