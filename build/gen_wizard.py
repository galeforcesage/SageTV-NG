# -*- coding: utf-8 -*-
"""
Generates the NG guided-conversion wizard as a full-screen two-column Menu
(cloning the native Detailed Setup: left category buttons, right live
"setting -> value" rows, help line at the bottom) plus a Create File popup
(cloning the native Convert dialog: file name, trim duration/start, a real
themed Delete-Original checkbox, and the Create button).

It patches stvs/SageTV7/SageTV7.xml in two places, backing up and validating
well-formedness after:
  1. The recording context item ("Export, Enhance & Archive", between the
     NGCONV-0001 and BASE-50396 markers) becomes a small launcher that creates
     the draft, promotes it + the starting area to global context, and jumps to
     the NG Menu.
  2. A new top-level <Menu ID=970001> is inserted just before the Detailed Setup
     menu (idempotently replaced on re-run).

State lives in the global-context variable NGDraft (an int draft id), plus the
per-menu Create File vars NGFileName / NGDelOrig / NGClipStart / NGClipDur.
Expressions are ASCII-only. Native theme/hook/action Refs are reused by number
so the screen matches the native Setup look exactly.
"""
import io, os, sys, datetime, re, xml.dom.minidom as minidom

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STV = os.path.join(REPO, "stvs", "SageTV7", "SageTV7.xml")

NGMENUID = 970001     # our full-screen Menu id (verified unused)
NGSWITCHID = 970050   # the right-column CurrSetupArea switch Conditional id
PROGRESS_ID = 970060  # ID injected onto the native "Conversion Started Dialog"
                      # (OptionsMenu BASE-50504) so we can open it by Ref
DLG_ACTIVE_ID = 970061  # our progress dialog's DialogActive Conditional (fork loop)
DLG_WAIT_ID = 970062    # our progress dialog's Wait(1000) node (fork loop)

# Reused native widget Refs (do not change; these resolve into SageTV7.xml):
THEME_MENU = 7168        # "Free Menu Layout with ..."
THEME_MENUCONTAINER = 7170  # "Free Form Horizontal ..."
THEME_LEFTBUTTONS = 22607   # "LeftButtonPanelTheme 7"
THEME_ROW = 5454         # "WideLeftRowPanelTheme ..." (label -> control row)
THEME_TEXTENTRY = 2072   # "TextEntryWithNTE_WideT..." (on-screen keyboard)
THEME_CONFIRM = 954      # "OptionsConfirmTheme" (checkbox / options popups)
THEME_PROGRESS = 2131    # "MutliSelectOptionsTheme" (native conversion dialog)
ACT_RENDERBUTTON = 22529 # renders a left button (highlight-if-active + text)
HOOK_FOCUSGAINED = 22543 # sets CurrSetupArea = SetupArea on focus
SHAPE_INPUTBORDER = 941  # "InputBorder"
COND_NUMERICTEXT = 2073  # "EnableNumericText"

_counter = [1000]
def sym():
    _counter[0] += 1
    return "NGCONV-%04d" % _counter[0]

def esc(s):
    return (s.replace("&", "&amp;").replace('"', "&quot;")
             .replace("<", "&lt;").replace(">", "&gt;"))

# Base indent (spaces before column 0 of the block). 47 for the context-menu
# launcher item, 1 for the top-level Menu.
_BASE = [47]
def line(depth, s):
    return (" " * _BASE[0]) + (" " * depth) + s + "\n"

# ---- primitive builders ------------------------------------------------------

def text_leaf(depth):
    return line(depth, '<ns0:Text Name="" Sym="%s" />' % sym())

def act(depth, name, children=None):
    """Action node. With children (a depth+1 callable) it executes on select and
    runs them; a bare Action with a Text child instead renders its evaluated Name."""
    s = sym()
    if not children:
        return line(depth, '<ns0:Action Name="%s" Sym="%s" />' % (esc(name), s))
    out = line(depth, '<ns0:Action Name="%s" Sym="%s">' % (esc(name), s))
    out += children(depth + 1)
    out += line(depth, '</ns0:Action>')
    return out

def just_refresh(depth):
    return act(depth, "Refresh()")

def close_and_refresh(depth):
    return act(depth, "CloseOptionsMenu()", lambda d: act(d, "Refresh()"))

def render(depth, expr):
    """An evaluated read: Action whose Name expression renders into a child Text."""
    return act(depth, expr, lambda d: text_leaf(d))

# Multi-line review/recommendation reports render several notches smaller than
# the default confirm-menu font so the whole summary fits without crowding.
SMALL_FONT = "=gFontSizeGeneral-6"
def render_small(depth, expr):
    def small_leaf(d):
        out = line(d, '<ns0:Text Name="" Sym="%s">' % sym())
        out += line(d + 1, '<ns0:IgnoreThemeProps>false</ns0:IgnoreThemeProps>')
        out += line(d + 1, '<ns0:Theme Name="NGReportFontTheme" Sym="%s">' % sym())
        out += line(d + 2, '<ns0:TileBackgroundImage>false</ns0:TileBackgroundImage>')
        out += line(d + 2, '<ns0:StretchBackgroundImage>false</ns0:StretchBackgroundImage>')
        out += line(d + 2, '<ns0:FontSize>%s</ns0:FontSize>' % SMALL_FONT)
        out += line(d + 1, '</ns0:Theme>')
        out += line(d, '</ns0:Text>')
        return out
    return act(depth, expr, small_leaf)

def item(depth, label, body):
    out = line(depth, '<ns0:Item Name="%s" Sym="%s">' % (esc(label), sym()))
    out += body(depth + 1)
    out += line(depth, '</ns0:Item>')
    return out

def panel(depth, name, body):
    out = line(depth, '<ns0:Panel Name="%s" Sym="%s">' % (esc(name), sym()))
    out += body(depth + 1)
    out += line(depth, '</ns0:Panel>')
    return out

def branch(depth, name_attr, body):
    out = line(depth, '<ns0:Branch Name="%s" Sym="%s">' % (esc(name_attr), sym()))
    out += body(depth + 1)
    out += line(depth, '</ns0:Branch>')
    return out

def attr(depth, name, value_expr):
    out = line(depth, '<ns0:Attribute Name="%s" Sym="%s">' % (name, sym()))
    out += line(depth + 1, '<ns0:Value>%s</ns0:Value>' % esc(value_expr))
    out += line(depth, '</ns0:Attribute>')
    return out

def optmenu(depth, name, body):
    """A confirm-styled popup OptionsMenu (theme 954)."""
    out = line(depth, '<ns0:OptionsMenu Name="%s" Sym="%s">' % (esc(name), sym()))
    out += line(depth + 1, '<ns0:IgnoreThemeProps>false</ns0:IgnoreThemeProps>')
    out += body(depth + 1)
    out += line(depth + 1, '<ns0:Theme Ref="%d" Name="OptionsConfirmTheme" />' % THEME_CONFIRM)
    out += line(depth, '</ns0:OptionsMenu>')
    return out

def cond(depth, expr, body):
    """A Conditional whose Name is a boolean expression; children render when true."""
    out = line(depth, '<ns0:Conditional Name="%s" Sym="%s">' % (esc(expr), sym()))
    out += body(depth + 1)
    out += line(depth, '</ns0:Conditional>')
    return out

def switch(depth, expr, branches):
    """A Conditional switch: `branches` is a list of (branch-name, body). Branch
    names that are string literals must include their own quotes."""
    out = line(depth, '<ns0:Conditional Name="%s" Sym="%s">' % (esc(expr), sym()))
    for name, body in branches:
        out += line(depth + 1, '<ns0:Branch Name="%s" Sym="%s">' % (esc(name), sym()))
        out += body(depth + 2)
        out += line(depth + 1, '</ns0:Branch>')
    out += line(depth, '</ns0:Conditional>')
    return out

def checkbox_item(depth, label, is_expr, toggle_expr):
    """A real themed checkbox row: the confirm theme draws the check glyph from the
    IsChecked attribute (a live pure-read expression); selecting runs the toggle."""
    out = line(depth, '<ns0:Item Name="%s" Sym="%s">' % (esc(label), sym()))
    out += attr(depth + 1, "IsChecked", is_expr)
    out += attr(depth + 1, "ButtonText", '"%s"' % label)
    out += act(depth + 1, toggle_expr, lambda d2: just_refresh(d2))
    out += line(depth, '</ns0:Item>')
    return out

def run_chain(depth, calls):
    """Nested actions that all fire on select (last one included). Requires >=2
    calls so the outer action has an executable child."""
    def build(idx, dp):
        if idx == len(calls) - 1:
            return act(dp, calls[idx])
        return act(dp, calls[idx], lambda d2, i=idx: build(i + 1, d2))
    return build(0, depth)

def close_refresh_chain(depth, calls):
    """Run calls, then CloseOptionsMenu()+Refresh() (return to and repaint parent)."""
    def build(idx, dp):
        if idx == len(calls):
            return close_and_refresh(dp)
        return act(dp, calls[idx], lambda d2, i=idx: build(i + 1, d2))
    return build(0, depth)

# ---- pickers (popup submenus) ------------------------------------------------

def picker_menu(depth, title, header_expr, options):
    """A confirm popup listing single-select options; each runs its call(s), then
    closes+refreshes the parent so the row's live value updates."""
    def body(dd):
        out = render(dd, header_expr) if header_expr else ""
        for label, action in options:
            calls = action if isinstance(action, list) else [action]
            out += item(dd, label, lambda ddd, c=calls: close_refresh_chain(ddd, c))
        out += item(dd, "Back", lambda ddd: close_and_refresh(ddd))
        return out
    return optmenu(depth, title, body)

def text_entry_menu(depth, title, prompt, var, numeric=False):
    """On-screen keyboard popup (theme 2072) whose TextInput writes directly to var."""
    out = line(depth, '<ns0:OptionsMenu Name="%s" Sym="%s">' % (esc(title), sym()))
    d = depth + 1
    out += line(d, '<ns0:IgnoreThemeProps>false</ns0:IgnoreThemeProps>')
    out += line(d, '<ns0:Listener Name="Select" Sym="%s">' % sym())
    out += line(d + 1, '<ns0:ListenerEvent>Select</ns0:ListenerEvent>')
    out += act(d + 1, "CloseOptionsMenu()", lambda dd: act(dd, "Refresh()"))
    out += line(d, '</ns0:Listener>')
    out += render(d, '"%s"' % prompt)
    out += line(d, '<ns0:Panel Name="InputArea" Sym="%s">' % sym())
    out += line(d + 1, '<ns0:Layout>Horizontal</ns0:Layout>')
    out += line(d + 1, '<ns0:FixedWidth>0.95</ns0:FixedWidth>')
    out += line(d + 1, '<ns0:IgnoreThemeProps>true</ns0:IgnoreThemeProps>')
    out += line(d + 1, '<ns0:TextInput Name="%s" Sym="%s">' % (var, sym()))
    out += line(d + 2, '<ns0:IgnoreThemeProps>false</ns0:IgnoreThemeProps>')
    out += line(d + 2, '<ns0:Shape Ref="%d" Name="InputBorder" />' % SHAPE_INPUTBORDER)
    out += line(d + 1, '</ns0:TextInput>')
    out += line(d, '</ns0:Panel>')
    out += line(d, '<ns0:Theme Ref="%d" Name="TextEntryWithNTE_WideT..." />' % THEME_TEXTENTRY)
    if numeric:
        out += line(d, '<ns0:Conditional Ref="%d" Name="EnableNumericText" />' % COND_NUMERICTEXT)
    out += line(depth, '</ns0:OptionsMenu>')
    return out

# ---- right-column rows -------------------------------------------------------

def picker_row(depth, label, value_expr, help, menu_title, header_expr, options):
    """A themed row: static label, live current value, opens a picker on select."""
    def body(d):
        out = line(d, '<ns0:Text Name="%s" Sym="%s" />' % (esc(label), sym()))
        out += item(d, "", lambda dd: (
            attr(dd, "HelpText", '"%s"' % help) +
            render(dd, value_expr) +
            picker_menu(dd, menu_title, header_expr, options)))
        out += line(d, '<ns0:Theme Ref="%d" Name="WideLeftRowPanelTheme ..." />' % THEME_ROW)
        return out
    return panel(depth, "Row", body)

def toggle_row(depth, label, display_expr, toggle_expr, help):
    """A themed on/off row: static label, live On/Off value, toggles on select."""
    def body(d):
        out = line(d, '<ns0:Text Name="%s" Sym="%s" />' % (esc(label), sym()))
        out += item(d, "", lambda dd: (
            attr(dd, "HelpText", '"%s"' % help) +
            render(dd, display_expr) +
            act(dd, toggle_expr, lambda d3: just_refresh(d3))))
        out += line(d, '<ns0:Theme Ref="%d" Name="WideLeftRowPanelTheme ..." />' % THEME_ROW)
        return out
    return panel(depth, "Row", body)

def notes_row(depth):
    """A themed row that opens a read-only compatibility-notes popup on select."""
    def body(d):
        out = line(d, '<ns0:Text Name="Compatibility notes" Sym="%s" />' % sym())
        out += item(d, "", lambda dd: (
            attr(dd, "HelpText", '"Player-compatibility warnings for the current choices."') +
            render(dd, '"View notes"') +
            optmenu(dd, "NG Compatibility Notes", lambda dd2:
                    render(dd2, 'GetDraftConflictReport(NGDraft)') +
                    item(dd2, "Back", lambda d3: close_and_refresh(d3)))))
        out += line(d, '<ns0:Theme Ref="%d" Name="WideLeftRowPanelTheme ..." />' % THEME_ROW)
        return out
    return panel(depth, "Row", body)

def readonly_area(depth, report_expr):
    """A right-column area that just renders a live report string."""
    def body(d):
        out = line(d, '<ns0:Layout>Vertical</ns0:Layout>')
        out += line(d, '<ns0:FixedWidth>1.0</ns0:FixedWidth>')
        out += line(d, '<ns0:IgnoreThemeProps>true</ns0:IgnoreThemeProps>')
        out += render_small(d, report_expr)
        return out
    return panel(depth, "AreaItems", body)

def area_items(depth, rows):
    """A vertical, scrolling container of right-column rows."""
    def body(d):
        out = line(d, '<ns0:Layout>Vertical</ns0:Layout>')
        out += line(d, '<ns0:FixedWidth>1.0</ns0:FixedWidth>')
        out += line(d, '<ns0:PadY>0.015</ns0:PadY>')
        out += line(d, '<ns0:Scrolling>1</ns0:Scrolling>')
        out += line(d, '<ns0:IgnoreThemeProps>true</ns0:IgnoreThemeProps>')
        for r in rows:
            out += r(d)
        return out
    return panel(depth, "AreaItems", body)

# ---- per-area row sets -------------------------------------------------------

def sel(field):
    return 'GetDraftSelection(NGDraft, "%s")' % field

def purpose_rows(d):
    intent = [("USB TV playback", 'SetDraftIntent(NGDraft, "USB_TV_PLAYBACK")'),
              ("Phone (offline)", 'SetDraftIntent(NGDraft, "PHONE_OFFLINE")'),
              ("Tablet (offline)", 'SetDraftIntent(NGDraft, "TABLET_OFFLINE")'),
              ("Travel download", 'SetDraftIntent(NGDraft, "WAN_SMALLER")'),
              ("Reusable favorite", 'SetDraftIntent(NGDraft, "REUSABLE_FAVORITE")'),
              ("Custom", 'SetDraftIntent(NGDraft, "custom")')]
    device = [("Phone", 'SetDraftDevice(NGDraft, "phone")'),
              ("Tablet", 'SetDraftDevice(NGDraft, "tablet")'),
              ("Computer", 'SetDraftDevice(NGDraft, "computer")'),
              ("Modern 4K TV", 'SetDraftDevice(NGDraft, "tv")'),
              ("Any device", 'SetDraftDevice(NGDraft, "unrestricted")'),
              ("Not sure", 'SetDraftDevice(NGDraft, "unknown")')]
    goals = [("Reduce storage", "REDUCE_STORAGE"),
             ("Improve / upscale picture", "IMPROVE_UPSCALE"),
             ("Preserve resolution & fps", "PRESERVE_RES_FPS"),
             ("Preserve surround audio", "PRESERVE_SURROUND"),
             ("Exact lossless backup", "EXACT_BACKUP")]
    out = picker_row(d, "Purpose", 'GetDraftIntentLabel(NGDraft)',
                     "What this file is for.", "NG Purpose",
                     '"Currently: " + GetDraftIntentLabel(NGDraft)', intent)
    out += picker_row(d, "Plays on", sel("device"),
                      "The device this will play on.", "NG Play Device",
                      '"Current: " + ' + sel("device"), device)
    for label, tok in goals:
        disp = 'If(GetDraftGoalEnabled(NGDraft, "%s"), "On", "Off")' % tok
        tog = 'SetDraftGoal(NGDraft, "%s", !GetDraftGoalEnabled(NGDraft, "%s"))' % (tok, tok)
        out += toggle_row(d, label, disp, tog, "Toggle this optimization goal.")
    return out

def transfer_rows(d):
    transfer = [("USB / local copy", 'SetDraftTransfer(NGDraft, "LOCAL_USB")'),
                ("Fast internet", 'SetDraftTransfer(NGDraft, "FAST_WAN")'),
                ("Limited / cellular", 'SetDraftTransfer(NGDraft, "LIMITED_WAN")'),
                ("No limit (home LAN)", 'SetDraftTransfer(NGDraft, "UNRESTRICTED")')]
    priority = [("Best picture", 'SetDraftPriority(NGDraft, "BEST_PICTURE")'),
                ("Balanced", 'SetDraftPriority(NGDraft, "BALANCED")'),
                ("Smaller file", 'SetDraftPriority(NGDraft, "SMALLER")'),
                ("Fastest", 'SetDraftPriority(NGDraft, "FASTEST")'),
                ("Max compatibility", 'SetDraftPriority(NGDraft, "MAX_COMPAT")'),
                ("Preserve source", 'SetDraftPriority(NGDraft, "PRESERVE_SOURCE")')]
    sizes = [("Recommended (auto)", ['SetDraftOverride(NGDraft, "width", "auto")',
                                      'SetDraftOverride(NGDraft, "height", "auto")']),
             ("4K UHD (3840 x 2160)", ['SetDraftOverride(NGDraft, "width", "3840")',
                                        'SetDraftOverride(NGDraft, "height", "2160")']),
             ("1440p (2560 x 1440)", ['SetDraftOverride(NGDraft, "width", "2560")',
                                       'SetDraftOverride(NGDraft, "height", "1440")']),
             ("1080p (1920 x 1080)", ['SetDraftOverride(NGDraft, "width", "1920")',
                                       'SetDraftOverride(NGDraft, "height", "1080")']),
             ("720p (1280 x 720)", ['SetDraftOverride(NGDraft, "width", "1280")',
                                     'SetDraftOverride(NGDraft, "height", "720")'])]
    out = picker_row(d, "Move it by", sel("transfer"),
                     "How you will move the file.", "NG Transfer",
                     '"Current: " + ' + sel("transfer"), transfer)
    out += picker_row(d, "Priority", sel("priority"),
                      "What matters most for this file.", "NG Priority",
                      '"Current: " + ' + sel("priority"), priority)
    out += picker_row(d, "Target size", sel("width") + ' + " x " + ' + sel("height"),
                      "Target resolution / quality.", "NG Target Size",
                      '"Current: " + ' + sel("width") + ' + " x " + ' + sel("height"), sizes)
    return out

def picture_rows(d):
    scaling = [("Recommended", 'SetDraftOverride(NGDraft, "scaling", "auto")'),
               ("None (keep size)", 'SetDraftOverride(NGDraft, "scaling", "NONE")'),
               ("Lanczos (sharpen scale)", 'SetDraftOverride(NGDraft, "scaling", "LANCZOS")'),
               ("AI upscale", 'SetDraftOverride(NGDraft, "scaling", "AI")')]
    dyn = [("Recommended", 'SetDraftOverride(NGDraft, "dynamicrange", "auto")'),
           ("Keep source", 'SetDraftOverride(NGDraft, "dynamicrange", "KEEP")'),
           ("Preserve HDR10", 'SetDraftOverride(NGDraft, "dynamicrange", "PRESERVE_HDR10")'),
           ("Tone-map to SDR", 'SetDraftOverride(NGDraft, "dynamicrange", "TONEMAP_SDR")')]
    out = picker_row(d, "Upscale", sel("scaling"),
                     "How to scale the picture up.", "NG Upscale",
                     '"Current: " + ' + sel("scaling"), scaling)
    out += picker_row(d, "Dynamic range", sel("dynamicrange"),
                      "HDR / dynamic-range handling.", "NG Dynamic Range",
                      '"Current: " + ' + sel("dynamicrange"), dyn)
    return out

def format_rows(d):
    container = [("Recommended", 'SetDraftOverride(NGDraft, "container", "auto")'),
                 ("MP4", 'SetDraftOverride(NGDraft, "container", "MP4")'),
                 ("MKV", 'SetDraftOverride(NGDraft, "container", "MKV")')]
    codec = [("Recommended", 'SetDraftOverride(NGDraft, "videocodec", "auto")'),
             ("H.264", 'SetDraftOverride(NGDraft, "videocodec", "H264")'),
             ("HEVC", 'SetDraftOverride(NGDraft, "videocodec", "HEVC")'),
             ("AV1", 'SetDraftOverride(NGDraft, "videocodec", "AV1")')]
    fps = [("Recommended", 'SetDraftOverride(NGDraft, "framerate", "auto")'),
           ("Keep source", 'SetDraftOverride(NGDraft, "framerate", "KEEP")'),
           ("Cap at 30", 'SetDraftOverride(NGDraft, "framerate", "CAP_30")'),
           ("Cap at 24", 'SetDraftOverride(NGDraft, "framerate", "CAP_24")'),
           ("Allow 60", 'SetDraftOverride(NGDraft, "framerate", "ALLOW_60")')]
    out = picker_row(d, "Container", sel("container"),
                     "Output container format.", "NG Container",
                     '"Current: " + ' + sel("container"), container)
    out += picker_row(d, "Video codec", sel("videocodec"),
                      "Output video codec.", "NG Video Codec",
                      '"Current: " + ' + sel("videocodec"), codec)
    out += picker_row(d, "Frame rate", sel("framerate"),
                      "Output frame-rate handling.", "NG Frame Rate",
                      '"Current: " + ' + sel("framerate"), fps)
    return out

def audio_rows(d):
    layout = [("Recommended", 'SetDraftOverride(NGDraft, "audiolayout", "auto")'),
              ("Keep source", 'SetDraftOverride(NGDraft, "audiolayout", "KEEP")'),
              ("Stereo", 'SetDraftOverride(NGDraft, "audiolayout", "STEREO")'),
              ("Surround 5.1", 'SetDraftOverride(NGDraft, "audiolayout", "SURROUND_51")')]
    codec = [("Recommended", 'SetDraftOverride(NGDraft, "audiocodec", "auto")'),
             ("Keep / copy", 'SetDraftOverride(NGDraft, "audiocodec", "COPY")'),
             ("AAC", 'SetDraftOverride(NGDraft, "audiocodec", "AAC")'),
             ("AC-3", 'SetDraftOverride(NGDraft, "audiocodec", "AC3")'),
             ("E-AC-3", 'SetDraftOverride(NGDraft, "audiocodec", "EAC3")')]
    subs = [("Recommended", 'SetDraftOverride(NGDraft, "subtitles", "auto")'),
            ("None", 'SetDraftOverride(NGDraft, "subtitles", "NONE")'),
            ("Copy", 'SetDraftOverride(NGDraft, "subtitles", "COPY")')]
    out = picker_row(d, "Audio layout", sel("audiolayout"),
                     "Channel layout for audio.", "NG Audio Layout",
                     '"Current: " + ' + sel("audiolayout"), layout)
    out += picker_row(d, "Audio codec", sel("audiocodec"),
                      "Output audio codec.", "NG Audio Codec",
                      '"Current: " + ' + sel("audiocodec"), codec)
    out += picker_row(d, "Subtitles", sel("subtitles"),
                      "Subtitle handling.", "NG Subtitles",
                      '"Current: " + ' + sel("subtitles"), subs)
    return out

def advanced_rows(d):
    prefs = [("Prefer compatibility", "goal", "PREFER_COMPAT"),
             ("Prefer smallest size", "goal", "PREFER_SMALLEST"),
             ("Prefer HDR", "goal", "PRESERVE_HDR"),
             ("Prefer fast encoding", "pref", "avoidreencode")]
    out = ""
    for label, kind, tok in prefs:
        if kind == "goal":
            disp = 'If(GetDraftGoalEnabled(NGDraft, "%s"), "On", "Off")' % tok
            tog = 'SetDraftGoal(NGDraft, "%s", !GetDraftGoalEnabled(NGDraft, "%s"))' % (tok, tok)
        else:
            disp = 'If(GetDraftPreferenceEnabled(NGDraft, "%s"), "On", "Off")' % tok
            tog = 'SetDraftPreference(NGDraft, "%s", !GetDraftPreferenceEnabled(NGDraft, "%s"))' % (tok, tok)
        out += toggle_row(d, label, disp, tog, "Toggle this encoding preference.")
    out += notes_row(d)
    return out

# ---- Create File popup -------------------------------------------------------

def _leave_to_list(depth):
    """Close this dialog and pop the guided wizard menu underneath, landing back on
    the recording list. SageCommand("Back") is deferred (processed after this action
    chain), so it correctly pops the wizard once the dialog is already closed -
    unlike opening a dialog then calling Back, which would close the dialog itself."""
    return act(depth, "CloseOptionsMenu()",
           lambda d: act(d, "DialogActive = false",
           lambda e: act(e, 'SageCommand("Back")')))

def progress_dialog(depth):
    """Our clone of the native "Conversion Started" dialog (BASE-50504): live status
    + percent that auto-refreshes via an AfterMenuLoad fork loop, a progress bar, and
    Continue/Cancel buttons. Unlike the native one (whose shared Continue/Cancel only
    CloseOptionsMenu), our buttons also pop the wizard so the user lands on the
    recording list. Reads global JobID / ConversionFormat set by the Create action."""
    def cancel_item(d):
        return item(d, "Cancel the Conversion",
                    lambda e: act(e, "CancelTranscodeJob(JobID)", _leave_to_list))
    def continue_item(d):
        return item(d, "Continue using SageTV", _leave_to_list)

    d1 = depth + 1
    out  = line(depth, '<ns0:OptionsMenu Name="NG Conversion Progress" Sym="%s">' % sym())
    out += line(d1, '<ns0:Layout>Vertical</ns0:Layout>')
    out += line(d1, '<ns0:AnchorX>0.5</ns0:AnchorX>')
    out += line(d1, '<ns0:AnchorY>0.5</ns0:AnchorY>')
    out += line(d1, '<ns0:FixedWidth>0.95</ns0:FixedWidth>')
    out += line(d1, '<ns0:PadY>0.01</ns0:PadY>')
    out += line(d1, '<ns0:Insets>=gDialogBGUIWidgetInsets</ns0:Insets>')
    out += line(d1, '<ns0:IgnoreThemeProps>true</ns0:IgnoreThemeProps>')
    # Info lines. The destination file name carries the show title, so we do not
    # depend on MediaFile/Airing being in scope here.
    out += render(d1, '"Video conversion in progress. You may wait for it to complete, or continue using SageTV.\\n "')
    out += render(d1, '"Conversion format: " + ConversionFormat')
    out += cond(d1, 'GetTranscodeJobDestFile(JobID) != null',
                lambda d: render(d, '"Destination file: " + GetTranscodeJobDestFile(JobID)'))
    # Live status + percent (recomputed on every refresh).
    out += act(d1, "ConversionStatus = GetTranscodeJobStatus(JobID)",
           lambda a: act(a, 'ConversionPercent = If(GetTranscodeJobStatus(JobID)=="TRANSCODING"," " + NumberFormat("0",GetTranscodeJobCompletePercent(JobID)*100) + "%","")',
           lambda b: render(b, '"Status: " + ConversionStatus + ConversionPercent')))
    # Progress bar while transcoding.
    def bar(d):
        out  = line(d, '<ns0:Panel Name="Conversion Percent Complete Panel" Sym="%s">' % sym())
        out += line(d + 1, '<ns0:FixedWidth>0.95</ns0:FixedWidth>')
        out += line(d + 1, '<ns0:FixedHeight>0.05</ns0:FixedHeight>')
        out += line(d + 1, '<ns0:BackgroundComponent>false</ns0:BackgroundComponent>')
        out += line(d + 1, '<ns0:Action Name="PercentDone = GetTranscodeJobCompletePercent(JobID)" Sym="%s">' % sym())
        out += line(d + 2, '<ns0:Shape Name="FillPercentDynamic" Sym="%s">' % sym())
        out += line(d + 3, '<ns0:ForegroundColor>0x00FF00</ns0:ForegroundColor>')
        out += line(d + 3, '<ns0:FixedWidth>=PercentDone</ns0:FixedWidth>')
        out += line(d + 3, '<ns0:FixedHeight>1.0</ns0:FixedHeight>')
        out += line(d + 3, '<ns0:ShapeType>Rectangle</ns0:ShapeType>')
        out += line(d + 3, '<ns0:ShapeFill>true</ns0:ShapeFill>')
        out += line(d + 2, '</ns0:Shape>')
        out += line(d + 1, '</ns0:Action>')
        out += line(d + 1, '<ns0:Shape Name="ConversionPercentDoneOutline" Sym="%s">' % sym())
        out += line(d + 2, '<ns0:ForegroundColor>0x00FF00</ns0:ForegroundColor>')
        out += line(d + 2, '<ns0:AnchorX>1.0</ns0:AnchorX>')
        out += line(d + 2, '<ns0:FixedWidth>2</ns0:FixedWidth>')
        out += line(d + 2, '<ns0:FixedHeight>1.0</ns0:FixedHeight>')
        out += line(d + 2, '<ns0:ShapeType>Rectangle</ns0:ShapeType>')
        out += line(d + 2, '<ns0:ShapeFill>false</ns0:ShapeFill>')
        out += line(d + 2, '<ns0:Thickness>2</ns0:Thickness>')
        out += line(d + 1, '</ns0:Shape>')
        out += line(d, '</ns0:Panel>')
        return out
    out += switch(d1, "GetTranscodeJobStatus(JobID)", [('"TRANSCODING"', bar)])
    # Buttons per status.
    out += switch(d1, "GetTranscodeJobStatus(JobID)", [
        ('"COMPLETED"',       continue_item),
        ('"TRANSCODING"',     lambda d: continue_item(d) + cancel_item(d)),
        ('"WAITING TO START"',lambda d: continue_item(d) + cancel_item(d)),
        ('"FAILED"',          cancel_item),
        ('else',              lambda d: continue_item(d) + cancel_item(d)),
    ])
    # Snapshot JobID; DialogActive drives the refresh loop; LastStatus seeds it.
    out += attr(d1, "JobID", "JobID")
    out += attr(d1, "DialogActive", "true")
    out += attr(d1, "LastStatus", '""')
    # Hardware "back"/options gesture also leaves cleanly to the recording list.
    out += line(d1, '<ns0:Listener Name="" Sym="%s">' % sym())
    out += line(d1 + 1, '<ns0:ListenerEvent>Options</ns0:ListenerEvent>')
    out += _leave_to_list(d1 + 1)
    out += line(d1, '</ns0:Listener>')
    # Seed LastStatus before first paint.
    out += line(d1, '<ns0:Hook Name="BeforeMenuLoad" Sym="%s">' % sym())
    out += act(d1 + 1, "LastStatus = GetTranscodeJobStatus(JobID)")
    out += line(d1, '</ns0:Hook>')
    # Fork loop: poll the job and RefreshArea whenever the status changes.
    out += line(d1, '<ns0:Hook Name="AfterMenuLoad" Sym="%s">' % sym())
    out += line(d1 + 1, '<ns0:Action Name="Fork()" Sym="%s">' % sym())
    out += line(d1 + 2, '<ns0:Conditional ID="%d" Name="DialogActive" Sym="%s">' % (DLG_ACTIVE_ID, sym()))
    out += line(d1 + 3, '<ns0:Action Name="CurStatus = GetTranscodeJobStatus(JobID)" Sym="%s">' % sym())
    out += line(d1 + 4, '<ns0:Conditional Name="LastStatus != CurStatus" Sym="%s">' % sym())
    out += line(d1 + 5, '<ns0:Branch Name="true" Sym="%s">' % sym())
    out += line(d1 + 6, '<ns0:Action Name="LastStatus = GetTranscodeJobStatus(JobID)" Sym="%s">' % sym())
    out += line(d1 + 7, '<ns0:Action Name="RefreshArea(&quot;NG Conversion Progress&quot;)" Sym="%s">' % sym())
    out += line(d1 + 8, '<ns0:Action Ref="%d" Name="Wait(1000)" />' % DLG_WAIT_ID)
    out += line(d1 + 7, '</ns0:Action>')
    out += line(d1 + 6, '</ns0:Action>')
    out += line(d1 + 5, '</ns0:Branch>')
    out += line(d1 + 5, '<ns0:Branch Name="else" Sym="%s">' % sym())
    out += line(d1 + 6, '<ns0:Action ID="%d" Name="Wait(1000)" Sym="%s">' % (DLG_WAIT_ID, sym()))
    out += line(d1 + 7, '<ns0:Conditional Ref="%d" Name="DialogActive" />' % DLG_ACTIVE_ID)
    out += line(d1 + 6, '</ns0:Action>')
    out += line(d1 + 5, '</ns0:Branch>')
    out += line(d1 + 4, '</ns0:Conditional>')
    out += line(d1 + 3, '</ns0:Action>')
    out += line(d1 + 2, '</ns0:Conditional>')
    out += line(d1 + 1, '</ns0:Action>')
    out += line(d1, '</ns0:Hook>')
    out += line(d1, '<ns0:Theme Ref="%d" Name="MutliSelectOptionsTheme" />' % THEME_PROGRESS)
    out += line(depth, '</ns0:OptionsMenu>')
    return out

def fail_menu(depth):
    """Shown when StartDraftConversion returns <= 0 (file in use / conflicting job)."""
    def body(dd):
        out  = render(dd, '"The conversion could not be started.\\n \\nThe file may be in use, or a conflicting job is already running."')
        out += item(dd, "OK", lambda d: act(d, "CloseOptionsMenu()"))
        return out
    return optmenu(depth, "NG Conversion Not Started", body)

def create_file_popup(depth):
    def body(dd):
        out = render_small(dd, 'GetDraftReviewReport(NGDraft)')
        # File name (free text)
        out += item(dd, "", lambda d: (
            attr(d, "HelpText", '"The output file name; the extension is added automatically."') +
            render(d, '"File name: " + NGFileName') +
            text_entry_menu(d, "NG File Name", "Enter a file name:", "NGFileName", numeric=True)))
        # Trim duration
        dur = [("Whole recording", "NGClipDur = 0"),
               ("First 5 minutes", "NGClipDur = 300"),
               ("First 15 minutes", "NGClipDur = 900"),
               ("First 30 minutes", "NGClipDur = 1800"),
               ("First 60 minutes", "NGClipDur = 3600")]
        out += item(dd, "", lambda d: (
            attr(d, "HelpText", '"How much of the recording to convert."') +
            render(d, '"Duration: " + If(NGClipDur == 0, "Whole recording", "" + (NGClipDur / 60) + " min")') +
            picker_menu(d, "NG Duration", None, dur)))
        # Trim start
        start = [("Beginning", "NGClipStart = 0"),
                 ("Skip 1 minute", "NGClipStart = 60"),
                 ("Skip 5 minutes", "NGClipStart = 300"),
                 ("Skip 10 minutes", "NGClipStart = 600")]
        out += item(dd, "", lambda d: (
            attr(d, "HelpText", '"Where in the recording to start."') +
            render(d, '"Start at: " + If(NGClipStart == 0, "Beginning", "" + (NGClipStart / 60) + " min")') +
            picker_menu(d, "NG Start", None, start)))
        # Delete-original toggle (rendered like the Duration/Start rows so it shows
        # its ON/off state as text; theme 954 has no checkbox glyph of its own).
        out += item(dd, "", lambda d: (
            attr(d, "HelpText", '"Delete the source recording after a successful conversion."') +
            render(d, '"Delete original when done:  " + If(NGDelOrig, "[ ON ]", "[ off ]")') +
            act(d, "NGDelOrig = !NGDelOrig", lambda d2: act(d2, "Refresh()"))))
        # Create + Cancel. Capturing the job id from StartDraftConversion lets us
        # show a progress/completion dialog (our progress_dialog clone) that polls
        # the job for live % / COMPLETED and offers Continue/Cancel. JobID +
        # ConversionFormat are promoted to global context so the dialog resolves
        # them after this popup closes. We must NOT SageCommand("Back") here: it is
        # deferred and would fire after the dialog opens, closing it - so instead
        # the dialog's own Continue/Cancel buttons pop the wizard to the list.
        def create_action(d):
            def started(a):
                out  = line(a, '<ns0:Conditional Name="JobID &gt; 0" Sym="%s">' % sym())
                out += line(a + 1, '<ns0:Branch Name="true" Sym="%s">' % sym())
                out += act(a + 2, 'AddGlobalContext("JobID", JobID)',
                           lambda b: act(b, 'AddGlobalContext("ConversionFormat", "NG Guided Conversion")',
                           lambda c: act(c, "CloseOptionsMenu()",
                           lambda e: progress_dialog(e))))
                out += line(a + 1, '</ns0:Branch>')
                out += line(a + 1, '<ns0:Branch Name="else" Sym="%s">' % sym())
                out += act(a + 2, "CloseOptionsMenu()", lambda b: fail_menu(b))
                out += line(a + 1, '</ns0:Branch>')
                out += line(a, '</ns0:Conditional>')
                return out
            return act(d, 'JobID = StartDraftConversion(NGDraft, MakeDraftDestFile(NGDraft, NGFileName), NGDelOrig, NGClipStart, NGClipDur)', started)
        out += item(dd, "Create File", create_action)
        out += item(dd, "Cancel", lambda d: act(d, "CloseOptionsMenu()"))
        return out
    return optmenu(depth, "NG Create File", body)

# ---- left buttons + right switch --------------------------------------------

# (SetupArea token, button label, help line, right-column rows builder)
AREAS = [
    ("xNgOverview", "Overview", "The recommended result and estimated output.",
     lambda d: readonly_area(d, 'GetDraftRecommendationReport(NGDraft) + "\\n \\n" + GetDraftReviewReport(NGDraft)')),
    ("xNgPurpose", "Purpose & Player", "Who this is for and where it will play.",
     lambda d: area_items(d, [purpose_rows])),
    ("xNgTransfer", "Transfer & File Size", "How you will move it and how big it should be.",
     lambda d: area_items(d, [transfer_rows])),
    ("xNgPicture", "Picture", "Upscaling and HDR / dynamic range.",
     lambda d: area_items(d, [picture_rows])),
    ("xNgFormat", "Video Format", "Container, video codec and frame rate.",
     lambda d: area_items(d, [format_rows])),
    ("xNgAudio", "Audio & Tracks", "Audio layout, codec and subtitles.",
     lambda d: area_items(d, [audio_rows])),
    ("xNgAdvanced", "Advanced", "Encoding preferences and compatibility notes.",
     lambda d: area_items(d, [advanced_rows])),
    ("xNgCreate", "Create File", "Name the file and start the conversion.",
     lambda d: readonly_area(d, '"Press OK to name the file and start.\\n \\n" + GetDraftReviewReport(NGDraft)')),
]

def left_button(depth, token, label, help, select_child=None, category=True):
    def body(d):
        out = ""
        if token:
            out += attr(d, "SetupArea", '"%s"' % token)
        out += attr(d, "AreaName", '"%s"' % label)
        out += attr(d, "HelpText", '"%s"' % help)
        out += line(d, '<ns0:Action Ref="%d" Name="REM render button" />' % ACT_RENDERBUTTON)
        if select_child:
            out += select_child(d)
        if category:
            out += line(d, '<ns0:Hook Ref="%d" Name="FocusGained" />' % HOOK_FOCUSGAINED)
        return out
    return item(depth, label, body)

def left_buttons(depth):
    def body(d):
        out = ""
        for token, label, help, _rows in AREAS:
            if token == "xNgCreate":
                # Category (shows summary on focus) that also opens the Create File
                # popup on select, initializing the popup vars first.
                def create_select(dd):
                    return act(dd, "NGFileName = GetDraftSuggestedName(NGDraft)",
                        lambda a: act(a, "NGDelOrig = false",
                        lambda b: act(b, "NGClipStart = 0",
                        lambda c: act(c, "NGClipDur = 0",
                        lambda e: create_file_popup(e)))))
                out += left_button(d, token, label, help, select_child=create_select)
            else:
                out += left_button(d, token, label, help)
        # Non-area action buttons.
        out += left_button(d, None, "Reset to Recommended",
                           "Clear all custom choices.",
                           select_child=lambda dd: run_chain(dd, ["ClearDraftOverrides(NGDraft)", "Refresh()"]),
                           category=False)
        out += left_button(d, None, "Cancel", "Discard and go back.",
                           select_child=lambda dd: run_chain(dd, ['DiscardConversionDraft(NGDraft)', 'SageCommand("Back")']),
                           category=False)
        out += line(d, '<ns0:Theme Ref="%d" Name="LeftButtonPanelTheme 7" />' % THEME_LEFTBUTTONS)
        return out
    return panel(depth, "Buttons", body)

def right_switch_body(depth):
    out = ""
    for token, label, help, rows in AREAS:
        out += branch(depth, '"%s"' % token, lambda dd, r=rows: r(dd))
    return out

def right_panel(depth):
    d = depth
    out = line(d, '<ns0:Panel Name="SetupAreas" Sym="%s">' % sym())
    out += line(d + 1, '<ns0:AnchorX>1.0</ns0:AnchorX>')
    out += line(d + 1, '<ns0:FixedWidth>0.7</ns0:FixedWidth>')
    out += line(d + 1, '<ns0:FixedHeight>1.0</ns0:FixedHeight>')
    out += line(d + 1, '<ns0:IgnoreThemeProps>true</ns0:IgnoreThemeProps>')
    out += line(d + 1, '<ns0:BackgroundComponent>false</ns0:BackgroundComponent>')
    out += line(d + 1, '<ns0:Conditional Name="GetFocusContext()" Sym="%s">' % sym())
    out += line(d + 2, '<ns0:Branch Name="true" Sym="%s">' % sym())
    out += line(d + 3, '<ns0:Conditional Name="SetupArea != null" Sym="%s">' % sym())
    out += line(d + 4, '<ns0:Branch Name="true" Sym="%s">' % sym())
    out += line(d + 5, '<ns0:Action Name="AddGlobalContext(&quot;CurrSetupArea&quot;, SetupArea)" Sym="%s">' % sym())
    out += line(d + 6, '<ns0:Conditional Ref="%d" Name="CurrSetupArea" />' % NGSWITCHID)
    out += line(d + 5, '</ns0:Action>')
    out += line(d + 4, '</ns0:Branch>')
    out += line(d + 4, '<ns0:Branch Name="false" Sym="%s">' % sym())
    out += line(d + 5, '<ns0:Conditional Ref="%d" Name="CurrSetupArea" />' % NGSWITCHID)
    out += line(d + 4, '</ns0:Branch>')
    out += line(d + 3, '</ns0:Conditional>')
    out += line(d + 2, '</ns0:Branch>')
    out += line(d + 2, '<ns0:Branch Name="false" Sym="%s">' % sym())
    out += line(d + 3, '<ns0:Conditional ID="%d" Name="CurrSetupArea" Sym="%s">' % (NGSWITCHID, sym()))
    out += right_switch_body(d + 4)
    out += line(d + 3, '</ns0:Conditional>')
    out += line(d + 2, '</ns0:Branch>')
    out += line(d + 1, '</ns0:Conditional>')
    out += line(d, '</ns0:Panel>')
    return out

# ---- assembly ----------------------------------------------------------------

def build_menu():
    _BASE[0] = 1
    out = line(0, '<ns0:Menu ID="%d" Name="NG Guided Conversion" Sym="%s">' % (NGMENUID, sym()))
    out += line(1, '<ns0:Theme Ref="%d" Name="Free Menu Layout with ..." />' % THEME_MENU)
    out += line(1, '<ns0:Hook Name="BeforeMenuUnload" Sym="%s">' % sym())
    out += act(2, "DiscardConversionDraft(NGDraft)")
    out += line(1, '</ns0:Hook>')
    out += line(1, '<ns0:Panel Name="MenuContainer" Sym="%s">' % sym())
    out += line(2, '<ns0:Theme Ref="%d" Name="Free Form Horizontal F..." />' % THEME_MENUCONTAINER)
    out += left_buttons(2)
    out += right_panel(2)
    out += line(1, '</ns0:Panel>')
    out += line(0, '</ns0:Menu>')
    return out

def launcher_chain(base, depth, syms):
    """Emit the launcher action chain (create draft -> promote NGDraft + start area
    to global context -> jump to Menu 970001) at an explicit base indent, using
    fixed Syms so re-runs are byte-identical. Shared by the in-dialog launcher Item
    and the single-recording direct-entry redirect."""
    old = _BASE[0]
    _BASE[0] = base
    out  = line(depth,   '<ns0:Action Name="%s" Sym="%s">' % (esc("NGDraft = NewConversionDraft(MediaFile)"), syms[0]))
    out += line(depth+1, '<ns0:Action Name="%s" Sym="%s">' % (esc('AddGlobalContext("NGDraft", NGDraft)'), syms[1]))
    out += line(depth+2, '<ns0:Action Name="%s" Sym="%s">' % (esc('AddGlobalContext("CurrSetupArea", "xNgOverview")'), syms[2]))
    out += line(depth+3, '<ns0:Menu Ref="%d" Name="NG Guided Conversion" />' % NGMENUID)
    out += line(depth+2, '</ns0:Action>')
    out += line(depth+1, '</ns0:Action>')
    out += line(depth,   '</ns0:Action>')
    _BASE[0] = old
    return out

def build_launcher():
    _BASE[0] = 47
    out = line(0, '<ns0:Item Name="%s" Sym="NGCONV-0001">' % esc("Export, Enhance & Archive"))
    out += launcher_chain(47, 1, ("NGCONV-1001", "NGCONV-1002", "NGCONV-1003"))
    out += line(0, '</ns0:Item>')
    return out

def splice_launcher(text):
    launcher = build_launcher()
    start_marker = '<ns0:Item Name="Export, Enhance &amp; Archive" Sym="NGCONV-0001">'
    end_marker = '<ns0:Item Name="Convert" Sym="BASE-50396">'
    if text.count(start_marker) != 1:
        print("ERROR: launcher start marker count =", text.count(start_marker)); sys.exit(1)
    if text.count(end_marker) != 1:
        print("ERROR: launcher end marker count =", text.count(end_marker)); sys.exit(1)
    iStart = text.index(start_marker)
    iEnd = text.index(end_marker)
    lineStart = text.rfind("\n", 0, iEnd) + 1  # keep indentation before Convert
    # The existing item may be glued onto the preceding <Branch> line (no newline),
    # so drop only the trailing whitespace before it and put the new item on its
    # own line; launcher lines already carry their own 47-space base indent.
    head = text[:iStart].rstrip(" \t")
    if not head.endswith("\n"):
        head += "\n"
    return head + launcher + text[lineStart:]

_TAG = re.compile(r'<(/?)ns0:[A-Za-z]+([^>]*?)(/?)>')
def _element_close(lines, start):
    """Given `start` = the line index where a (non-self-closing) ns0 element opens,
    return the line index where it closes, by tracking generic element nesting.
    Correctly ignores self-closing tags such as <ns0:Menu Ref=.. /> and
    <ns0:OptionsMenu Ref=.. />."""
    depth = 0
    for k in range(start, len(lines)):
        for m in _TAG.finditer(lines[k]):
            if m.group(1) == '/':
                depth -= 1
            elif m.group(3) != '/':
                depth += 1
        if k > start and depth == 0:
            return k
        if k == start and depth == 0:
            return k
    print("ERROR: could not find element close from line", start); sys.exit(1)

def _unwrap_redirect(text):
    """Reverse a previous splice_redirect so the operation is idempotent, restoring
    the untouched OPUS4A-125049 subtree."""
    if 'Sym="NGCONV-0100"' not in text:
        return text
    lines = text.split("\n")
    oi = next(i for i, l in enumerate(lines) if 'Sym="NGCONV-0100"' in l)
    ei = next(i for i, l in enumerate(lines) if 'Sym="NGCONV-0102"' in l)
    mi = ei + 1
    if 'Sym="OPUS4A-125049"' not in lines[mi]:
        print("ERROR: unwrap: line after NGCONV-0102 is not OPUS4A-125049"); sys.exit(1)
    action_close = _element_close(lines, mi)
    # Drop the guard-open block (oi..mi-1) and the two guard-close tags
    # (action_close+1, action_close+2); keep the 125049 subtree verbatim.
    new_lines = lines[:oi] + lines[mi:action_close + 1] + lines[action_close + 3:]
    return "\n".join(new_lines)

def splice_redirect(text):
    """Make a single non-favorite, non-multiple conversion jump straight to the
    guided Menu 970001 instead of the native chooser. Wraps the native chooser's
    open action (OPUS4A-125049 -> OptionsMenu BASE-50378) in a guard Conditional
    whose true branch is the launcher chain and whose else branch is the untouched
    native chooser (still used for favorite/multiple conversions)."""
    text = _unwrap_redirect(text)
    marker = '<ns0:Action ID="38871" Name="&quot;REM Open Conversion option dialog.&quot;" Sym="OPUS4A-125049">'
    if text.count(marker) != 1:
        print("ERROR: redirect marker count =", text.count(marker)); sys.exit(1)
    lines = text.split("\n")
    mi = next(i for i, l in enumerate(lines) if marker in l)
    indent = len(lines[mi]) - len(lines[mi].lstrip(" "))
    action_close = _element_close(lines, mi)
    pad = " " * indent
    body = launcher_chain(indent + 2, 0, ("NGCONV-0110", "NGCONV-0111", "NGCONV-0112")).rstrip("\n").split("\n")
    gopen = ([pad + '<ns0:Conditional Name="!IsFavoriteConversion &amp;&amp; !IsMultipleConversion" Sym="NGCONV-0100">',
              pad + ' <ns0:Branch Name="true" Sym="NGCONV-0101">']
             + body
             + [pad + ' </ns0:Branch>',
                pad + ' <ns0:Branch Name="else" Sym="NGCONV-0102">'])
    gclose = [pad + ' </ns0:Branch>', pad + '</ns0:Conditional>']
    new_lines = lines[:mi] + gopen + lines[mi:action_close + 1] + gclose + lines[action_close + 1:]
    return "\n".join(new_lines)

def splice_menu(text):
    menu = build_menu()
    open_marker = '<ns0:Menu ID="%d" ' % NGMENUID
    ds_marker = '<ns0:Menu ID="863" Name="Detailed Setup"'
    if open_marker in text:
        # Replace our previously-inserted menu (from its opening tag to the first
        # top-level </ns0:Menu>, which is ours since we nest no full Menu elements).
        iOpen = text.index(open_marker)
        lineOpen = text.rfind("\n", 0, iOpen) + 1
        close_tag = "</ns0:Menu>"
        iClose = text.index(close_tag, iOpen) + len(close_tag)
        # consume trailing newline
        if text[iClose:iClose + 1] == "\n":
            iClose += 1
        return text[:lineOpen] + menu + text[iClose:]
    if text.count(ds_marker) != 1:
        print("ERROR: Detailed Setup marker count =", text.count(ds_marker)); sys.exit(1)
    iDS = text.index(ds_marker)
    lineDS = text.rfind("\n", 0, iDS) + 1
    return text[:lineDS] + menu + text[lineDS:]

def splice_progress_id(text):
    """We now clone the progress dialog rather than referencing the native one, so
    revert any ID we previously injected onto BASE-50504, keeping it pristine.
    Idempotent."""
    injected = '<ns0:OptionsMenu ID="%d" Name="Conversion Started Dialog" Sym="BASE-50504">' % PROGRESS_ID
    pristine = '<ns0:OptionsMenu Name="Conversion Started Dialog" Sym="BASE-50504">'
    if injected in text:
        return text.replace(injected, pristine)
    return text

def main():
    with io.open(STV, "r", encoding="utf-8") as f:
        text = f.read()

    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    bak = os.path.join(REPO, "build", "stv-backups", "SageTV7.pre-wizard-%s.xml" % stamp)
    os.makedirs(os.path.dirname(bak), exist_ok=True)
    with io.open(bak, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    print("Backup:", bak)

    new_text = splice_launcher(text)
    new_text = splice_redirect(new_text)
    new_text = splice_progress_id(new_text)
    new_text = splice_menu(new_text)

    with io.open(STV, "w", encoding="utf-8", newline="") as f:
        f.write(new_text)

    try:
        minidom.parse(STV)
        print("XML well-formed OK")
    except Exception as e:
        print("XML PARSE FAILED, restoring backup:", e)
        with io.open(bak, "r", encoding="utf-8") as f:
            orig = f.read()
        with io.open(STV, "w", encoding="utf-8", newline="") as f:
            f.write(orig)
        sys.exit(2)

    print("New file size:", os.path.getsize(STV))

if __name__ == "__main__":
    main()
