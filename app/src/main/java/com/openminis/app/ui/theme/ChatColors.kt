package com.openminis.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// Semantic chat colors mirroring iOS ChatColors (AIChatView.swift).
// Resolved from LocalChatPalette, which is provided by MinisTheme.
//
// iOS reference:
//   systemBackground        -> background
//   secondarySystemBackground -> secondaryBg
//   tertiarySystemFill      -> userBubble
//   tertiarySystemGroupedBackground -> toolBg
//   label                   -> primaryText
//   secondaryLabel          -> secondaryText
//   tertiaryLabel           -> tertiaryText
//   quaternaryLabel         -> sendButtonDisabled
//   separator               -> border
//   systemGray6             -> inlineCodeBg / toolCapsuleBg
@Immutable
data class ChatPalette(
    val isDark: Boolean,
    val background: Color,
    val secondaryBg: Color,
    val inputBg: Color,
    val inputIconBg: Color,
    val inputIconBorder: Color,
    val inputBorder: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    val disabledText: Color,
    val userBubble: Color,
    // nanoMuse: the text ink on the user bubble, and the assistant's own pill
    // (a touch off the background, both themes).
    val onUserBubble: Color,
    val assistantBubble: Color,
    val toolBg: Color,
    val toolBorder: Color,
    val toolCapsuleBg: Color,
    val separator: Color,
    val sendButton: Color,
    val sendButtonDisabled: Color,
    val codeBlockBg: Color,
    val codeBlockText: Color,
    val inlineCodeBg: Color,
    val inlineCodeText: Color,
    val link: Color,
    val blockquoteBar: Color,
    val thinking: Color,
    val warningBg: Color,
    val warningText: Color,
    val tableBorder: Color,
    val inputShadow: Color,
    val toastBg: Color,
    val thumbnailBorder: Color,
    val sheetHeaderBg: Color,
    val sheetHeaderBorder: Color,
    val fabAccent: Color,
)

val LightChatPalette = ChatPalette(
    isDark = false,
    background = Color(0xFFFFFBF7),
    secondaryBg = Color(0xFFF6EFE8),
    inputBg = Color(0xFFFFFFFF),
    inputIconBg = Color(0xFFF6EFE8),
    inputIconBorder = Color.Transparent,
    inputBorder = Color(0x40B08C78),
    primaryText = Color(0xFF2B211C),
    secondaryText = Color(0x995C4A40),
    tertiaryText = Color(0x665C4A40),
    disabledText = Color(0x335C4A40),
    // nanoMuse: the user's own message is a black pill with white ink (white
    // pill, black ink in the dark theme below). scripts/rebrand.py writes these.
    userBubble = Color(0xFFF9E1D6),
    onUserBubble = Color(0xFF3A2A22),
    assistantBubble = Color(0xFFF6EFE8),
    toolBg = Color(0xFFF6EFE8),
    toolBorder = Color(0x1A8A5A44),
    toolCapsuleBg = Color(0xFFF3E9E0),
    separator = Color(0x33B08C78),
    sendButton = Color(0xFFE07F5F),
    sendButtonDisabled = Color(0x33B08C78),
    codeBlockBg = Color(0xFF2A221E),
    codeBlockText = Color(0xFFF3E9E0),
    inlineCodeBg = Color(0xFFF6EFE8),
    inlineCodeText = Color(0xFFC2593A),
    link = Color(0xFFC8613F),
    blockquoteBar = Color(0x80E07F5F),
    thinking = Color(0xFFE07F5F),
    warningBg = Color(0x1AF2A33A),
    warningText = Color(0x99000000),
    tableBorder = Color(0x24B08C78),
    inputShadow = Color.Transparent,
    toastBg = Color(0x33E07F5F),
    thumbnailBorder = Color(0x33808080),
    sheetHeaderBg = Color(0xFFFFFFFF),
    sheetHeaderBorder = Color(0x1A8A5A44),
    fabAccent = Color(0xFFE07F5F),
)

// T153: Android-specific dark palette tweaks. iOS borrows the system
// palette (#1C1C1E / #2C2C2E etc.) which reads as "layered dark grey"
// on a 1000+ nit display, but on a typical Android phone (Pixel 6 ≈
// 500 nits, mid-range OEMs even less) those layers crush together
// into a single near-black wash and the user can't tell tool capsules
// from background or input from message list. Lift the non-background
// layers ~6-10% so the contrast survives the brightness gap; the pure
// `background` itself stays #000 because every other color is keyed
// to "darker than this".
val DarkChatPalette = ChatPalette(
    isDark = true,
    background = Color(0xFF1C1816),
    secondaryBg = Color(0xFF2A2420),
    inputBg = Color(0xFF2E2723),
    inputIconBg = Color(0xFF241F1C),
    inputIconBorder = Color(0xFF5A4C44),
    inputBorder = Color(0x40A08878),
    primaryText = Color(0xFFF6EEE8),
    secondaryText = Color(0x99F1E4DA),
    tertiaryText = Color(0x55F1E4DA),
    disabledText = Color(0x2EF1E4DA),
    // nanoMuse: the user's own message is a white pill with black ink; the
    // assistant's pill is a touch off the pure-black wash. (Replaces the earlier
    // opaque slate-blue user bubble.) scripts/rebrand.py writes these.
    userBubble = Color(0xFF4A332A),
    onUserBubble = Color(0xFFFBEDE5),
    assistantBubble = Color(0xFF262120),
    toolBg = Color(0xFF352D29),
    toolBorder = Color(0x40A08878),
    toolCapsuleBg = Color(0xFF2C2522),
    separator = Color(0x80544640),
    sendButton = Color(0xFFF0A285),
    sendButtonDisabled = Color(0x2EF1E4DA),
    codeBlockBg = Color(0xFF141110),
    codeBlockText = Color(0xFFF3E9E0),
    // [T-inline-code-dark-bg-android] Lifted into the T153 ramp — the old
    // #1C1C1E chip was invisible on the #000 chat background (the comment
    // block above explains why non-background layers need the ~6-10% lift;
    // this one was missed). #34343A sits between inputBg (#2C2C30) and
    // toolBg (#3A3A3F), clearly above codeBlockBg (#262626) so small inline
    // chips read against both the wash and fenced blocks.
    inlineCodeBg = Color(0xFF3A312C),
    inlineCodeText = Color(0xFFF4AE93),
    link = Color(0xFFF4AE93),
    blockquoteBar = Color(0x80F0A285),
    thinking = Color(0xFFF0A285),
    warningBg = Color(0x14FF9F0A),
    warningText = Color(0x73FFFFFF),
    tableBorder = Color(0xFF4A3F39),
    inputShadow = Color(0x80000000),
    toastBg = Color(0x33F0A285),
    thumbnailBorder = Color(0x20545458),
    sheetHeaderBg = Color(0xFF2E2723),
    sheetHeaderBorder = Color(0x33FFFFFF),
    fabAccent = Color(0xFFF0A285),
)

val LocalChatPalette = compositionLocalOf { LightChatPalette }

// Short accessor: ChatColors.primaryText instead of LocalChatPalette.current.primaryText
val ChatColors: ChatPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalChatPalette.current
