; SageTV Placeshifter - Inno Setup Script
; Builds a Windows installer for the SageTV Placeshifter (MiniClient)
; Requires: Inno Setup 6.x
; Build with: ISCC.exe SageTVPlaceshifter.iss

#define MyAppName "SageTV Placeshifter"
#define MyAppVersion "9.5.0"
#define MyAppPublisher "SageTV"
#define MyAppURL "https://github.com/google/sagetv"
#define MyAppExeName "javaw.exe"
#define StagingDir "..\build\placeshifter-staging"
#define JavaOpts "-Xms128m -Xmx512m -XX:+UseZGC -XX:+ZGenerational -Dsun.java2d.d3d=true -Dsun.java2d.opengl=false --add-opens java.desktop/sun.awt=ALL-UNNAMED --add-opens java.desktop/sun.java2d=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED"

[Setup]
AppId={{8A2F5B3E-9C4D-4E6F-A1B2-3C4D5E6F7A8B}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
DefaultDirName={autopf}\SageTV Placeshifter
DefaultGroupName={#MyAppName}
AllowNoIcons=yes
LicenseFile={#StagingDir}\LICENSE
OutputDir=..\build
OutputBaseFilename=SageTVPlaceshifter_{#MyAppVersion}_Setup
SetupIconFile={#StagingDir}\SageIcon.ico
UninstallDisplayIcon={app}\SageIcon.ico
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
MinVersion=10.0
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "german"; MessagesFile: "compiler:Languages\German.isl"
Name: "french"; MessagesFile: "compiler:Languages\French.isl"
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"
Name: "japanese"; MessagesFile: "compiler:Languages\Japanese.isl"
Name: "dutch"; MessagesFile: "compiler:Languages\Dutch.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
; Application JARs and dependencies
Source: "{#StagingDir}\lib\*"; DestDir: "{app}\lib"; Flags: ignoreversion recursesubdirs

; Bundled Java 21 Runtime (jlinked minimal JRE)
Source: "{#StagingDir}\runtime\*"; DestDir: "{app}\runtime"; Flags: ignoreversion recursesubdirs

; Native binaries - video player and DirectX/OpenGL support
Source: "{#StagingDir}\SageTVPlayer.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#StagingDir}\SageTVDX93D.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#StagingDir}\SageTVWin32.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#StagingDir}\ImageLoader.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#StagingDir}\pthreadGC2.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#StagingDir}\swscale.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#StagingDir}\SageTVInfraredReceive.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#StagingDir}\Win32ShellHook.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#StagingDir}\WinKeyboardHook.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#StagingDir}\WinRawInput.dll"; DestDir: "{app}"; Flags: ignoreversion

; Icon and license files
Source: "{#StagingDir}\SageIcon.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#StagingDir}\LICENSE"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#StagingDir}\ThirdPartyLicense.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "{#JavaOpts} -cp ""{app}\lib\*"" sage.miniclient.MiniClient"; WorkingDir: "{app}"; IconFilename: "{app}\SageIcon.ico"; Comment: "Launch SageTV Placeshifter"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "{#JavaOpts} -cp ""{app}\lib\*"" sage.miniclient.MiniClient"; WorkingDir: "{app}"; IconFilename: "{app}\SageIcon.ico"; Comment: "Launch SageTV Placeshifter"; Tasks: desktopicon

[Run]
Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "{#JavaOpts} -cp ""{app}\lib\*"" sage.miniclient.MiniClient"; Description: "{cm:LaunchProgram,{#MyAppName}}"; Flags: nowait postinstall skipifsilent
