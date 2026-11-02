# Ultra HDR 2.0.2's XMP writer rejects per-channel and alternate-space gain
# metadata. Keep XMP + ISO for compatible maps, ISO only for the others.
# Adapt a build-tree copy rather than mutating the fetched dependency.
file(READ "${libultrahdr_SOURCE_DIR}/lib/src/jpegr.cpp" uhdr_source)
string(FIND "${uhdr_source}" "uhdr_error_info_t JpegR::appendGainMap(" append_begin)
string(FIND "${uhdr_source}" "uhdr_error_info_t JpegR::getJPEGRInfo(" append_end)
if(append_begin LESS 0 OR append_end LESS_EQUAL append_begin)
    message(FATAL_ERROR "Ultra HDR gain-map metadata adaptation no longer matches")
endif()
math(EXPR append_length "${append_end}-${append_begin}")
string(SUBSTRING "${uhdr_source}" 0 ${append_begin} append_prefix)
string(SUBSTRING "${uhdr_source}" ${append_begin} ${append_length} append_body)
string(SUBSTRING "${uhdr_source}" ${append_end} -1 append_suffix)
string(REPLACE "kWriteXmpMetadata" "writeXmpMetadata" append_body "${append_body}")
set(append_signature "uhdr_compressed_image_t* dest) {")
string(FIND "${append_body}" "${append_signature}" signature_at)
if(signature_at LESS 0)
    message(FATAL_ERROR "Ultra HDR appendGainMap signature no longer matches")
endif()
string(REPLACE "${append_signature}"
    "${append_signature}\n  const bool writeXmpMetadata = kWriteXmpMetadata && metadata->use_base_cg && metadata->are_all_channels_identical();"
    append_body "${append_body}")
file(CONFIGURE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/uhdr_jpegr.cpp"
    CONTENT "${append_prefix}${append_body}${append_suffix}" @ONLY)
get_target_property(uhdr_objects core SOURCES)
set(uhdr_original "${libultrahdr_SOURCE_DIR}/lib/src/jpegr.cpp")
if(NOT uhdr_original IN_LIST uhdr_objects)
    message(FATAL_ERROR "Ultra HDR core source list no longer matches")
endif()
list(REMOVE_ITEM uhdr_objects "${uhdr_original}")
set_property(TARGET core PROPERTY SOURCES
    "${uhdr_objects};${CMAKE_CURRENT_BINARY_DIR}/uhdr_jpegr.cpp")
