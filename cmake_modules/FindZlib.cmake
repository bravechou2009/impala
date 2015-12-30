# ZLIB_ROOT hints the location
# Provides
# - ZLIB,
# - ZLIB_LIBRARIES,
# - ZLIB_STATIC,
# - ZLIB_FOUND

set(_ZLIB_SEARCH_DIR)
if (ZLIB_ROOT)
set(_ZLIB_SEARCH_DIR PATHS ${ZLIB_ROOT} NO_DEFAULT_PATH)
endif()

find_path(ZLIB_INCLUDE_DIR zlib.h ${_ZLIB_SEARCH_DIR}
  PATH_SUFFIXES include)

find_library(ZLIB_STATIC_LIBRARIES libz.a
  ${_ZLIB_SEARCH_DIR} PATH_SUFFIXES lib lib64)

if (ZLIB_STATIC_LIBRARIES)
  add_library(ZLIB_STATIC STATIC IMPORTED)
  set_target_properties(ZLIB_STATIC PROPERTIES
    IMPORTED_LOCATION ${ZLIB_STATIC_LIBRARIES})
  set(ZLIB_STATIC_FOUND ON)
else()
  set(ZLIB_STATIC_FOUND OFF)
  set(ZLIB_STATIC ${ZLIB_STATIC_LIBRARIES})
endif()

set(ZLIB_NAMES z zlib zdll zlib1 zlibd zlibd1)
find_library(ZLIB_LIBRARIES ${ZLIB_NAMES}
  ${_ZLIB_SEARCH_DIR} PATH_SUFFIXES lib lib64)

if (NOT ZLIB_LIBRARIES AND NOT ZLIB_STATIC_LIBRARIES)
  message(FATAL_ERROR "zlib not found in ${ZLIB_ROOT}")
  set(ZLIB_FOUND FALSE)
else()
  message(STATUS "Zlib: ${ZLIB_INCLUDE_DIR}")
  set(ZLIB_FOUND TRUE)
endif()

mark_as_advanced(
  ZLIB_INCLUDE_DIR
  ZLIB_LIBRARIES
  ZLIB_STATIC
  ZLIB_STATIC_FOUND
)