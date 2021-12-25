# PiHive

Simple raspberry Pi based beehive monitor, a netbeans project in java, 
currently using single temperature sensor and hx711 weight cell.
Requires local wlan access to show state (tomcat9) and to deliver log to 
any ftp accessible server, usually the beekeepers homepage.

Known problems:
Pulsetiming for HX711 readout using java (nonrealtime) is quite difficult.
JVM seems to require up to minutes to stabilize cpu cycles per pulse.



