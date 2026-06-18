#requires -Version 5.1
<#
  regen-smb-keyboard.ps1
  ----------------------
  Replace the SMB Authentication soft keyboard with a full QWERTY layout
  (Caps / Lower / Symbols modes, all same row shape), add a field-target
  selector (Username / Password / Domain), shorter mode + toggle labels with
  explicit widths so they stop truncating, and Backspace / Space control keys.

  Safety: backup -> textual replacements -> XML validate -> auto-restore.
#>

param([switch]$ValidateOnly,[switch]$KeyboardOnly)

$ErrorActionPreference = 'Stop'
$Stv = Resolve-Path (Join-Path $PSScriptRoot '..\stvs\SageTV7\SageTV7.xml')

function Read-Utf8([string]$p){ [IO.File]::ReadAllText($p) }
function Write-Utf8([string]$p,[string]$t){ [IO.File]::WriteAllText($p,$t,[Text.UTF8Encoding]::new($false)) }
function Test-Xml([string]$p){
  try { [xml]$null = Get-Content -LiteralPath $p -Raw -Encoding UTF8; $true }
  catch { Write-Warning ("XML invalid: " + $_.Exception.Message); $false }
}

if($ValidateOnly){
  if(Test-Xml $Stv){ Write-Host "XML OK"; exit 0 } else { exit 1 }
}

$ts     = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = "$Stv.bak-smbkbd-$ts"
Copy-Item -LiteralPath $Stv -Destination $backup -Force
Write-Host "Backup: $backup"

$c = Read-Utf8 $Stv
$orig = $c

# ---------- helpers --------------------------------------------------------
function Replace-Once([ref]$buf,[string]$find,[string]$repl,[string]$label){
  $count = ([regex]::Matches($buf.Value,[regex]::Escape($find))).Count
  if($count -ne 1){ throw "[$label] expected exactly 1 match, found $count" }
  $buf.Value = $buf.Value.Replace($find, $repl)
}
function X([string]$s){
  ($s -replace '&','&amp;') -replace '<','&lt;' -replace '>','&gt;' -replace '"','&quot;' -replace "'",'&apos;'
}

# ---------- Edit 1: insert SMBAuthField + SMBAuthPendingChar init ---------
if(-not $KeyboardOnly){
$find1 = @"
                                <Action Name="SMBAuthShowPassword = false" Sym="BASE-47324-AUTH-SHOW-INIT">
                                 <Action Name="KeyboardMode = 0" Sym="BASE-47324-KEYBOARD-MODE-INIT-OPEN">
"@
$repl1 = @"
                                <Action Name="SMBAuthShowPassword = false" Sym="BASE-47324-AUTH-SHOW-INIT">
                                 <Action Name="SMBAuthField = 0" Sym="BASE-47324-AUTH-FIELD-INIT">
                                  <Action Name="SMBAuthPendingChar = &quot;&quot;" Sym="BASE-47324-AUTH-PENDING-INIT">
                                   <Action Name="KeyboardMode = 0" Sym="BASE-47324-KEYBOARD-MODE-INIT-OPEN">
"@
Replace-Once ([ref]$c) $find1 $repl1 'init-wrap'

# ---------- Edit 2: add 2 closing </Action> for the new wrappers ----------
$find2 = @"
                                  </OptionsMenu>
                                 </Action>
                                </Action>
                               </Action>
                              </Action>
                             </Action>
                            </Action>
                           </Branch>
"@
$repl2 = @"
                                  </OptionsMenu>
                                 </Action>
                                  </Action>
                                 </Action>
                                </Action>
                               </Action>
                              </Action>
                             </Action>
                            </Action>
                           </Branch>
"@
Replace-Once ([ref]$c) $find2 $repl2 'init-close'

# ---------- Edit 3: shorten Show Password toggle + widen ------------------
$find3 = @"
                                     <Item Name="Toggle" Sym="BASE-47324-AUTH-TOGGLE-BTN">
                                      <Action Name="SMBAuthShowPassword = !SMBAuthShowPassword" Sym="BASE-47324-AUTH-TOGGLE-ACTION">
                                       <Action Name="Refresh()" Sym="BASE-47324-AUTH-TOGGLE-REFRESH"/>
                                      </Action>
                                      <Conditional Name="SMBAuthShowPassword" Sym="BASE-47324-AUTH-TOGGLE-STATE">
                                       <Branch Name="true" Sym="BASE-47324-AUTH-TOGGLE-ON">
                                        <Text Name="Enabled" Sym="BASE-47324-AUTH-TOGGLE-TEXT-ON"/>
                                       </Branch>
                                       <Branch Name="false" Sym="BASE-47324-AUTH-TOGGLE-OFF">
                                        <Text Name="Disabled" Sym="BASE-47324-AUTH-TOGGLE-TEXT-OFF"/>
                                       </Branch>
                                      </Conditional>
                                      <Theme Ref="5454" Name="WideLeftRowPanelTheme ..."/>
                                     </Item>
"@
$repl3 = @"
                                     <Item Name="Toggle" Sym="BASE-47324-AUTH-TOGGLE-BTN">
                                      <FixedWidth>0.30</FixedWidth>
                                      <Action Name="SMBAuthShowPassword = !SMBAuthShowPassword" Sym="BASE-47324-AUTH-TOGGLE-ACTION">
                                       <Action Name="Refresh()" Sym="BASE-47324-AUTH-TOGGLE-REFRESH"/>
                                      </Action>
                                      <Conditional Name="SMBAuthShowPassword" Sym="BASE-47324-AUTH-TOGGLE-STATE">
                                       <Branch Name="true" Sym="BASE-47324-AUTH-TOGGLE-ON">
                                        <Text Name="On" Sym="BASE-47324-AUTH-TOGGLE-TEXT-ON"/>
                                       </Branch>
                                       <Branch Name="false" Sym="BASE-47324-AUTH-TOGGLE-OFF">
                                        <Text Name="Off" Sym="BASE-47324-AUTH-TOGGLE-TEXT-OFF"/>
                                       </Branch>
                                      </Conditional>
                                      <Theme Ref="5454" Name="WideLeftRowPanelTheme ..."/>
                                     </Item>
"@
Replace-Once ([ref]$c) $find3 $repl3 'show-pass-toggle'
} # end -not $KeyboardOnly

# ---------- Edit 4: full replacement of SoftKeyboard panel ---------------
# Locate bounds in current buffer
$startMarker = '                                    <Panel Name="SoftKeyboard" Sym="BASE-47324-AUTH-KEYBOARD">'
$endMarker   = '                                   <Item Name="Send" Sym="BASE-47324-AUTH-SEND">'
$si = $c.IndexOf($startMarker);   if($si -lt 0){ throw 'SoftKeyboard start marker not found' }
$ei = $c.IndexOf($endMarker);     if($ei -lt 0){ throw 'Send marker not found' }
if($ei -le $si){ throw 'End marker before start marker' }

# Key data ------------------------------------------------------------------
$capsRows  = ,('Q','W','E','R','T','Y','U','I','O','P') +
             ,('A','S','D','F','G','H','J','K','L')      +
             ,('Z','X','C','V','B','N','M')
$lowerRows = ,('q','w','e','r','t','y','u','i','o','p') +
             ,('a','s','d','f','g','h','j','k','l')      +
             ,('z','x','c','v','b','n','m')
$symRows   = ,('!','@','#','$','%','^','&','*','(',')') +
             ,('-','_','+','=','[',']','{','}','|','~') +
             ,(';',':','''','"',',','.','/','\','<','>')
$digitRow  = ,('0','1','2','3','4','5','6','7','8','9')

# Indentation (matches existing file)
$P0 = ' ' * 36   # SoftKeyboard panel
$P1 = ' ' * 37
$P2 = ' ' * 38
$P3 = ' ' * 39
$P4 = ' ' * 40
$P5 = ' ' * 41
$P6 = ' ' * 42
$P7 = ' ' * 43
$P8 = ' ' * 44

$sb = [Text.StringBuilder]::new()
function L($s){ [void]$script:sb.AppendLine($s) }

# Routing logic emitted inline in FIRST key; subsequent keys reference ID 73990.
$ROUTING_ID = 73990
$routingDefined = $false

function EmitKey([string]$mode,[string]$ch,[int]$ord){
  $code = '{0:X2}' -f ([int][char]$ch[0])
  $sym  = "BASE-47324-K-$mode-$code-$ord"
  $xch  = X $ch
  $assign = "SMBAuthPendingChar = &quot;$xch&quot;"
  if(-not $script:routingDefined){
    $script:routingDefined = $true
    L ($P3 + "<Item Name=`"$xch`" Sym=`"$sym`">")
    L ($P4 +  "<Action Name=`"$assign`" Sym=`"$sym-SET`">")
    L ($P5 +   "<Conditional ID=`"$ROUTING_ID`" Name=`"SMBAuthField == 0`" Sym=`"BASE-47324-ROUTE-COND0`">")
    L ($P6 +    "<Branch Name=`"true`" Sym=`"BASE-47324-ROUTE-USER`">")
    L ($P7 +     "<Action Name=`"SMBAuthUsername = SMBAuthUsername + SMBAuthPendingChar`" Sym=`"BASE-47324-ROUTE-USER-ACT`"/>")
    L ($P6 +    "</Branch>")
    L ($P6 +    "<Branch Name=`"false`" Sym=`"BASE-47324-ROUTE-NOT-USER`">")
    L ($P7 +     "<Conditional Name=`"SMBAuthField == 1`" Sym=`"BASE-47324-ROUTE-COND1`">")
    L ($P8 +      "<Branch Name=`"true`" Sym=`"BASE-47324-ROUTE-PASS`">")
    L (($P8+' ') + "<Action Name=`"SMBAuthPassword = SMBAuthPassword + SMBAuthPendingChar`" Sym=`"BASE-47324-ROUTE-PASS-ACT`"/>")
    L ($P8 +      "</Branch>")
    L ($P8 +      "<Branch Name=`"false`" Sym=`"BASE-47324-ROUTE-DOMAIN`">")
    L (($P8+' ') + "<Action Name=`"SMBAuthDomain = SMBAuthDomain + SMBAuthPendingChar`" Sym=`"BASE-47324-ROUTE-DOMAIN-ACT`"/>")
    L ($P8 +      "</Branch>")
    L ($P7 +     "</Conditional>")
    L ($P6 +    "</Branch>")
    L ($P5 +   "</Conditional>")
    L ($P4 +  "</Action>")
    L ($P3 + "</Item>")
  } else {
    L ($P3 + "<Item Name=`"$xch`" Sym=`"$sym`"><Action Name=`"$assign`" Sym=`"$sym-SET`"><Conditional Ref=`"$ROUTING_ID`" Name=`"SMBAuthField == 0`"/></Action></Item>")
  }
}

function EmitGridRows([string]$mode,[string]$gridSymBase,[array]$rows){
  for($r=0; $r -lt $rows.Count; $r++){
    $row = $rows[$r]
    $cols = $row.Count
    L ($P2 + "<Panel Name=`"$mode-Row$r`" Sym=`"$gridSymBase-R$r`">")
    L ($P3 +  "<Layout>HorizontalGrid</Layout>")
    L ($P3 +  "<FixedWidth>1.0</FixedWidth>")
    L ($P3 +  "<NumCols>$cols</NumCols>")
    L ($P3 +  "<PadX>0.005</PadX>")
    L ($P3 +  "<PadY>0.003</PadY>")
    L ($P3 +  "<WrapHNav>true</WrapHNav>")
    L ($P3 +  "<WrapVNav>false</WrapVNav>")
    L ($P3 +  "<IgnoreThemeProps>true</IgnoreThemeProps>")
    L ($P3 +  "<BackgroundComponent>false</BackgroundComponent>")
    for($k=0; $k -lt $cols; $k++){ EmitKey $mode $row[$k] ($r*10+$k) }
    L ($P2 + "</Panel>")
  }
}

# --- SoftKeyboard panel ---------------------------------------------------
L ($P0 + '<Panel Name="SoftKeyboard" Sym="BASE-47324-AUTH-KEYBOARD">')
L ($P1 +  '<Layout>Vertical</Layout>')
L ($P1 +  '<FixedWidth>1.0</FixedWidth>')
L ($P1 +  '<PadY>0.005</PadY>')
L ($P1 +  '<WrapHNav>false</WrapHNav>')
L ($P1 +  '<WrapVNav>false</WrapVNav>')
L ($P1 +  '<IgnoreThemeProps>true</IgnoreThemeProps>')
L ($P1 +  '<BackgroundComponent>false</BackgroundComponent>')
L ($P1 +  '<MouseTransparency>false</MouseTransparency>')

# Field selector row removed - user clicks directly into target text box.
# Each TextInput has a Hook FocusGained that sets SMBAuthField so the soft
# keyboard routing (which reads SMBAuthField) follows the last-focused field.

# Keyboard mode row
L ($P1 + '<Panel Name="KeyboardModeRow" Sym="BASE-47324-KEYBOARD-MODE">')
L ($P2 +  '<Text Name="Mode:" Sym="BASE-47324-KEYBOARD-MODE-LABEL"/>')
L ($P2 +  '<Item Name="NextMode" Sym="BASE-47324-KEYBOARD-MODE-BTN">')
L ($P3 +   '<FixedWidth>0.30</FixedWidth>')
L ($P3 +   '<Action Name="KeyboardMode = (KeyboardMode + 1) % 3" Sym="BASE-47324-KEYBOARD-MODE-TOGGLE">')
L ($P4 +    '<Action Name="Refresh()" Sym="BASE-47324-KEYBOARD-MODE-REFRESH"/>')
L ($P3 +   '</Action>')
L ($P3 +   '<Conditional Name="KeyboardMode == 0" Sym="BASE-47324-MODE-DISPLAY-CAPS">')
L ($P4 +    '<Branch Name="true" Sym="BASE-47324-MODE-DISPLAY-CAPS-TRUE"><Text Name="ABC" Sym="BASE-47324-MODE-CAPS-TEXT"/></Branch>')
L ($P3 +   '</Conditional>')
L ($P3 +   '<Conditional Name="KeyboardMode == 1" Sym="BASE-47324-MODE-DISPLAY-LOWER">')
L ($P4 +    '<Branch Name="true" Sym="BASE-47324-MODE-DISPLAY-LOWER-TRUE"><Text Name="abc" Sym="BASE-47324-MODE-LOWER-TEXT"/></Branch>')
L ($P3 +   '</Conditional>')
L ($P3 +   '<Conditional Name="KeyboardMode == 2" Sym="BASE-47324-MODE-DISPLAY-SYM">')
L ($P4 +    '<Branch Name="true" Sym="BASE-47324-MODE-DISPLAY-SYM-TRUE"><Text Name="!@#" Sym="BASE-47324-MODE-SYM-TEXT"/></Branch>')
L ($P3 +   '</Conditional>')
L ($P3 +   '<Theme Ref="5454" Name="WideLeftRowPanelTheme ..."/>')
L ($P2 +  '</Item>')
L ($P1 + '</Panel>')

# Digit row (always visible)
L ($P1 + '<Panel Name="DigitRow" Sym="BASE-47324-DIGIT-ROW">')
L ($P2 +  '<Layout>HorizontalGrid</Layout>')
L ($P2 +  '<FixedWidth>1.0</FixedWidth>')
L ($P2 +  '<NumCols>10</NumCols>')
L ($P2 +  '<PadX>0.005</PadX>')
L ($P2 +  '<PadY>0.003</PadY>')
L ($P2 +  '<WrapHNav>true</WrapHNav>')
L ($P2 +  '<WrapVNav>false</WrapVNav>')
L ($P2 +  '<IgnoreThemeProps>true</IgnoreThemeProps>')
L ($P2 +  '<BackgroundComponent>false</BackgroundComponent>')
# Promote P2..P5 -> P3..P6 for keys inside digit row
$bk = @($P3,$P4,$P5,$P6,$P7,$P8)
$P3=$P3; $P4=$P4; $P5=$P5; $P6=$P6; $P7=$P7; $P8=$P8
for($k=0; $k -lt $digitRow[0].Count; $k++){ EmitKey 'DIG' $digitRow[0][$k] $k }
L ($P1 + '</Panel>')

# Helper to emit a grid conditional block (mode N)
function EmitModeBlock([int]$mode,[string]$modeName,[string]$gridSymBase,[array]$rows){
  L ($P1 + "<Conditional Name=`"KeyboardMode == $mode`" Sym=`"$gridSymBase`">")
  L ($P2 +  "<Branch Name=`"true`" Sym=`"$gridSymBase-TRUE`">")
  L ($P3 +   "<Panel Name=`"Keyboard$modeName`" Sym=`"$gridSymBase-GRID`">")
  L ($P4 +    "<Layout>Vertical</Layout>")
  L ($P4 +    "<FixedWidth>1.0</FixedWidth>")
  L ($P4 +    "<PadY>0.002</PadY>")
  L ($P4 +    "<WrapHNav>false</WrapHNav>")
  L ($P4 +    "<WrapVNav>false</WrapVNav>")
  L ($P4 +    "<IgnoreThemeProps>true</IgnoreThemeProps>")
  L ($P4 +    "<BackgroundComponent>false</BackgroundComponent>")
  # shift indent context: rows inside this inner panel should be at $P4 (Panel children = $P5...)
  $script:P0s=$P0; $script:P1s=$P1; $script:P2s=$P2; $script:P3s=$P3; $script:P4s=$P4; $script:P5s=$P5; $script:P6s=$P6; $script:P7s=$P7; $script:P8s=$P8
  $script:P2 = ' ' * 40   # row Panel indent (inside inner grid panel which is at 39)
  $script:P3 = ' ' * 41   # row children + Items
  $script:P4 = ' ' * 42
  $script:P5 = ' ' * 43
  $script:P6 = ' ' * 44
  $script:P7 = ' ' * 45
  $script:P8 = ' ' * 46
  EmitGridRows $modeName $gridSymBase $rows
  # restore
  $script:P0=$script:P0s; $script:P1=$script:P1s; $script:P2=$script:P2s; $script:P3=$script:P3s; $script:P4=$script:P4s; $script:P5=$script:P5s; $script:P6=$script:P6s; $script:P7=$script:P7s; $script:P8=$script:P8s
  L ($P3 +   "</Panel>")
  L ($P2 +  "</Branch>")
  L ($P1 + "</Conditional>")
}

EmitModeBlock 0 'Caps'  'BASE-47324-KEYBOARD-CAPS'    $capsRows
EmitModeBlock 1 'Lower' 'BASE-47324-KEYBOARD-LOWER'   $lowerRows
EmitModeBlock 2 'Syms'  'BASE-47324-KEYBOARD-SYMBOLS' $symRows

# Control keys row
L ($P1 + '<Panel Name="ControlKeys" Sym="BASE-47324-KEYBOARD-CONTROLS">')
L ($P2 +  '<Layout>Horizontal</Layout>')
L ($P2 +  '<FixedWidth>1.0</FixedWidth>')
L ($P2 +  '<PadX>0.01</PadX>')
L ($P2 +  '<PadY>0.005</PadY>')
L ($P2 +  '<WrapHNav>false</WrapHNav>')
L ($P2 +  '<WrapVNav>false</WrapVNav>')
L ($P2 +  '<IgnoreThemeProps>true</IgnoreThemeProps>')
L ($P2 +  '<BackgroundComponent>false</BackgroundComponent>')
# Tab key removed - focus jump requires structural changes that would hide
# 2 of the 3 text boxes; user clicks the target text box directly.
# Backspace (per-field) - glyph U+232B "erase to the left"
L ($P2 + '<Item Name="&#x232B; Backspace" Sym="BASE-47324-KEY-BACKSPACE">')
L ($P3 +  '<FixedWidth>0.30</FixedWidth>')
L ($P3 +  '<Conditional Name="SMBAuthField == 0" Sym="BASE-47324-BS-COND0">')
L ($P4 +   '<Branch Name="true" Sym="BASE-47324-BS-USER"><Action Name="SMBAuthUsername = If(Size(SMBAuthUsername) &gt; 0, Substring(SMBAuthUsername, 0, Size(SMBAuthUsername) - 1), SMBAuthUsername)" Sym="BASE-47324-BS-USER-ACT"/></Branch>')
L ($P4 +   '<Branch Name="false" Sym="BASE-47324-BS-NOT-USER">')
L ($P5 +    '<Conditional Name="SMBAuthField == 1" Sym="BASE-47324-BS-COND1">')
L ($P6 +     '<Branch Name="true" Sym="BASE-47324-BS-PASS"><Action Name="SMBAuthPassword = If(Size(SMBAuthPassword) &gt; 0, Substring(SMBAuthPassword, 0, Size(SMBAuthPassword) - 1), SMBAuthPassword)" Sym="BASE-47324-BS-PASS-ACT"/></Branch>')
L ($P6 +     '<Branch Name="false" Sym="BASE-47324-BS-DOM"><Action Name="SMBAuthDomain = If(Size(SMBAuthDomain) &gt; 0, Substring(SMBAuthDomain, 0, Size(SMBAuthDomain) - 1), SMBAuthDomain)" Sym="BASE-47324-BS-DOM-ACT"/></Branch>')
L ($P5 +    '</Conditional>')
L ($P4 +   '</Branch>')
L ($P3 +  '</Conditional>')
L ($P2 + '</Item>')
# Space (uses shared router via Ref) - glyph U+23B5 "open box"
L ($P2 + '<Item Name="&#x23B5; Space" Sym="BASE-47324-KEY-SPACE">')
L ($P3 +  '<FixedWidth>0.30</FixedWidth>')
L ($P3 +  '<Action Name="SMBAuthPendingChar = &quot; &quot;" Sym="BASE-47324-KEY-SPACE-SET">')
L ($P4 +   '<Conditional Ref="73990" Name="SMBAuthField == 0"/>')
L ($P3 +  '</Action>')
L ($P2 + '</Item>')
# Enter / Submit - glyph U+21B5 "carriage return" - mirrors BASE-47324-AUTH-SEND
L ($P2 + '<Item Name="&#x21B5; Enter" Sym="BASE-47324-KEY-ENTER">')
L ($P3 +  '<FixedWidth>0.30</FixedWidth>')
L ($P3 +  '<Action Name="SetSMBBrowseCredentials(SMBAuthUsername, SMBAuthPassword, SMBAuthDomain, false)" Sym="BASE-47324-KEY-ENTER-SET">')
L ($P4 +   '<Action Name="CurrentDirPath = ReplaceAll(java_lang_Object_toString(SMBAuthHost), &quot;\\&quot;, &quot;/&quot;)" Sym="BASE-47324-KEY-ENTER-NAV">')
L ($P5 +    '<Action Ref="53218" Name="IsValidDirBrowserDir = true"/>')
L ($P5 +    '<Action Name="SortedDirFiles = null" Sym="BASE-47324-KEY-ENTER-CLEAR">')
L ($P6 +     '<Action Name="Refresh()" Sym="BASE-47324-KEY-ENTER-REFRESH">')
L ($P7 +      '<Action Name="CloseOptionsMenu()" Sym="BASE-47324-KEY-ENTER-CLOSE"/>')
L ($P6 +     '</Action>')
L ($P5 +    '</Action>')
L ($P4 +   '</Action>')
L ($P3 +  '</Action>')
L ($P2 + '</Item>')
# Clear All (existing nested-Action style)
L ($P2 + '<Item Name="Clear All" Sym="BASE-47324-KEY-CLEAR">')
L ($P3 +  '<FixedWidth>0.30</FixedWidth>')
L ($P3 +  '<Action Name="SMBAuthUsername = &quot;&quot;" Sym="BASE-47324-CLEAR-USER">')
L ($P4 +   '<Action Name="SMBAuthPassword = &quot;&quot;" Sym="BASE-47324-CLEAR-PASS">')
L ($P5 +    '<Action Name="SMBAuthDomain = &quot;&quot;" Sym="BASE-47324-CLEAR-DOMAIN">')
L ($P6 +     '<Action Name="Refresh()" Sym="BASE-47324-CLEAR-REFRESH"/>')
L ($P5 +    '</Action>')
L ($P4 +   '</Action>')
L ($P3 +  '</Action>')
L ($P2 + '</Item>')
L ($P1 + '</Panel>')

L ($P0 + '</Panel>')                                # closes SoftKeyboard
L ((' ' * 35) + '</Panel>')                          # closes AuthInputArea

$newBlock = $sb.ToString()

# Replace from start of SoftKeyboard line up to (but not including) the Send Item line
$siLine = $c.LastIndexOf("`n", $si) + 1
$eiLine = $c.LastIndexOf("`n", $ei) + 1
$pre  = $c.Substring(0, $siLine)
$post = $c.Substring($eiLine)
$c = $pre + $newBlock + $post

# ---------- Write + validate -----------------------------------------------
Write-Utf8 $Stv $c
Write-Host "Wrote: $Stv"

if(-not (Test-Xml $Stv)){
  Write-Warning "XML INVALID - restoring backup"
  Copy-Item -LiteralPath $backup -Destination $Stv -Force
  throw "Validation failed; original restored from $backup"
}

# Sanity counts
$markers = ([regex]::Matches((Read-Utf8 $Stv),'BASE-47324-')).Count
Write-Host ("BASE-47324-* marker count: {0}" -f $markers) -ForegroundColor Green
$keys = ([regex]::Matches((Read-Utf8 $Stv),'BASE-47324-K-')).Count
Write-Host ("BASE-47324-K-* key Item count: {0} (expected 92)" -f $keys) -ForegroundColor Green
Write-Host "OK." -ForegroundColor Green
