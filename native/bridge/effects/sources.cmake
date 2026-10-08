# SPDX-License-Identifier: GPL-2.0-or-later
# Sources of the bridge "effects" module (see native/bridge/MODULES.md):
# effects.* and analyze.* (API.md §3.3, §5.4, §5.5).
set(AUBRIDGE_EFFECTS_SOURCES
   ${CMAKE_CURRENT_LIST_DIR}/RegisterEffectsModule.cpp
   ${CMAKE_CURRENT_LIST_DIR}/EffectsInternal.h
   ${CMAKE_CURRENT_LIST_DIR}/BuiltinEffects.cpp
   ${CMAKE_CURRENT_LIST_DIR}/EffectLabels.cpp
   ${CMAKE_CURRENT_LIST_DIR}/EffectSchema.cpp
   ${CMAKE_CURRENT_LIST_DIR}/EffectMenus.cpp
   ${CMAKE_CURRENT_LIST_DIR}/EffectApply.cpp
   ${CMAKE_CURRENT_LIST_DIR}/EffectPreview.cpp
   ${CMAKE_CURRENT_LIST_DIR}/Analyzers.cpp
)
