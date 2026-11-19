; Reglas de firewall con nombre claro, para que Windows no pregunte con un nombre genérico.
; Necesitan permisos de administrador; si se instala solo para el usuario, fallan sin avisar
; y Windows mostrará su aviso habitual la primera vez.

!macro NSIS_HOOK_POSTINSTALL
  nsExec::Exec 'netsh advfirewall firewall delete rule name="ShareDrop - transferencia de archivos (TCP 47800)"'
  nsExec::Exec 'netsh advfirewall firewall delete rule name="ShareDrop - descubrimiento de equipos (UDP 47801)"'
  nsExec::Exec 'netsh advfirewall firewall add rule name="ShareDrop - transferencia de archivos (TCP 47800)" dir=in action=allow protocol=TCP localport=47800 program="$INSTDIR\sharedrop-desktop.exe" profile=private description="ShareDrop recibe archivos de tu celular u otra PC en la red local."'
  nsExec::Exec 'netsh advfirewall firewall add rule name="ShareDrop - descubrimiento de equipos (UDP 47801)" dir=in action=allow protocol=UDP localport=47801 program="$INSTDIR\sharedrop-desktop.exe" profile=private description="ShareDrop encuentra tus otros equipos en la red local."'
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  nsExec::Exec 'netsh advfirewall firewall delete rule name="ShareDrop - transferencia de archivos (TCP 47800)"'
  nsExec::Exec 'netsh advfirewall firewall delete rule name="ShareDrop - descubrimiento de equipos (UDP 47801)"'
!macroend
