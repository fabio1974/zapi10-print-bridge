; Zapi10 Print Bridge - Inno Setup script
; Compila pra um .exe instalador unico para Windows.
; Buildado pelo GitHub Actions (workflow .github/workflows/release.yml).
;
; Pra testar localmente num Windows:
;   1) Instalar Inno Setup 6+ (https://jrsoftware.org/isdl.php)
;   2) Abrir este .iss e clicar Compile
;   3) Sai Output\Zapi10PrintBridgeSetup.exe

#define MyAppName "Zapi10 Print Bridge"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "Zapi10"
#define MyAppExeName "print-bridge.exe"

[Setup]
AppId={{A4F32E11-5C7B-4D9A-8E1F-9A5B7C8D6E12}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\Zapi10PrintBridge
DefaultGroupName=Zapi10
DisableProgramGroupPage=yes
DisableDirPage=yes
OutputBaseFilename=Zapi10PrintBridgeSetup
OutputDir=Output
Compression=lzma
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
WizardStyle=modern
UninstallDisplayIcon={app}\print-bridge.exe
SetupIconFile=
LicenseFile=

[Languages]
Name: "brazilianportuguese"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Files]
; Os arquivos sao copiados pelo build CI antes do compilar
Source: "..\windows\print-bridge.jar";    DestDir: "{app}"; Flags: ignoreversion
Source: "..\windows\print-bridge.exe";    DestDir: "{app}"; Flags: ignoreversion
Source: "..\windows\print-bridge.xml";    DestDir: "{app}"; Flags: ignoreversion
Source: "..\windows\LEIA-ME.txt";         DestDir: "{app}"; Flags: ignoreversion isreadme
Source: "..\windows\view-logs.bat";       DestDir: "{app}"; Flags: ignoreversion
Source: "..\windows\jre\*";               DestDir: "{app}\jre"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Zapi10 Print Bridge - Ver Logs"; Filename: "{app}\view-logs.bat"; WorkingDir: "{app}"
Name: "{group}\Desinstalar Zapi10 Print Bridge"; Filename: "{uninstallexe}"

[Run]
Filename: "{app}\print-bridge.exe"; Parameters: "stop"; Flags: runhidden waituntilterminated; StatusMsg: "Parando servico anterior..."
Filename: "{app}\print-bridge.exe"; Parameters: "uninstall"; Flags: runhidden waituntilterminated; StatusMsg: "Removendo instalacao anterior..."
Filename: "{app}\print-bridge.exe"; Parameters: "install"; Flags: runhidden waituntilterminated; StatusMsg: "Instalando servico Windows..."
Filename: "netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Zapi10 Print Bridge"""; Flags: runhidden waituntilterminated
Filename: "netsh.exe"; Parameters: "advfirewall firewall add rule name=""Zapi10 Print Bridge"" dir=in action=allow protocol=TCP localport=9100"; Flags: runhidden waituntilterminated; StatusMsg: "Abrindo porta 9100 no firewall..."
Filename: "{app}\print-bridge.exe"; Parameters: "start"; Flags: runhidden waituntilterminated; StatusMsg: "Iniciando servico..."
Filename: "{app}\LEIA-ME.txt"; Description: "Abrir LEIA-ME"; Flags: postinstall shellexec skipifsilent unchecked

[UninstallRun]
Filename: "{app}\print-bridge.exe"; Parameters: "stop"; Flags: runhidden waituntilterminated
Filename: "{app}\print-bridge.exe"; Parameters: "uninstall"; Flags: runhidden waituntilterminated
Filename: "netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Zapi10 Print Bridge"""; Flags: runhidden waituntilterminated

[UninstallDelete]
Type: filesandordirs; Name: "{app}\logs"
