
# Anwenderinformation

## Stromanschluß

Stromversorgung 5V stabilisiert mittels USB Stecker. Mittels z.B. Telefonladegerät kann an das Hausnetz, oder mittels Zigarettenanzünder-Adapter an eine 12V Batterie angeschlossen werden.
Für Solarpanels ist die Zwischenschaltung eines Pufferakkus empfohlen.  Das Verhalten bei langsamen Absinken (abend) bzw. Aufbau (morgens) der Versorgungsspannung wurde nicht getestet.
Falls der Solarregler schnell ab und sicher  bei ausreichender Helligkeit wieder einschaltet und garantiert nicht "pendelt" ist ein Betrieb mit Nachtabschaltung möglich. 

## Netzwerkanschluß/WLAN

Die Waage benötigt WLAN-Zugang. Die Zugangsinformation ist im Gerät vorkonfiguriert.
Das Gerät versucht nach Herstellung des Stomanschlusses mit einem Accesspoint MyHomeAP unter Verwendung des Passworts #PiHive# eine WLAN-Verbindung herzustellen.
Zur Integration in das eventuell vorhandene Heimnetzwerk bestehen 2 Möglichkeiten:

1. Installation eines zusätzlichen WLAN-Accespoints (Rangeextender, z.B. Netgear, oder Powerlineadapter, z.B. von Devolo) mit Name MyHomeAP, geschützt mit dem genannten WPA2/PSK Passwort #PiHive#.
Das Problem: Dadurch wird ein Zugang zu ihrem Heimnetz eingerichtet der praktisch öffentlich bekannt ist.

2. Temporäre Installation eines Routers (z.B. mittels Einstellung "Hotspot" auf Android-Telefonen) und Anpassung der der Zugangsinformation mit Texteditor unter Verwendung des SSH-Zugangs (Windows: puttty).
Nach Trennen und Wiederherstellen der Stromversorgung versucht das Gerät sich  mit dem angegebenen Heimnetz zu verbinden.
Empfehlenswert ist es die vorkonfigurierte Zugangsinformation im Gerät zu belassen sodaß bei Fehleingabe Korrekturmöglichkeit bleibt.

Falls sich das Gerät nach Adaptierung entsprechend 2.) weder am Behelfsrouter noch am Heimnetz anmeldet muß das Gerät geöffnet werden, die MicroSD in einen Computer gesteckt und dort mit Texteditor die Änderung durchgeführt werden.  

Zugriff auf das Gerät mittels SSH ist i.A.  40 Sekunden nach Stomanschluß möglich, die Webansicht (PiHive-Applikation) ist erst nach ca. 12 Minuten bereit.

## Waagenkalibrierung

Die Waage ist nicht geeicht, kann aber wie folgt kalibriert werden:

1. In der Applikation "Waage" wählen, das akutelle Gewicht des Stock eingeben und "Tara" drücken.

2. Innerhalb von 10 Minuten ein Normgewicht (mindestens 5kg) zusätzlich auflegen, das neue Gesamtgewicht eingeben und nochmals "Tara" drücken, danach das Zusatzgewicht wieder entfernen.

Während Arbeiten am Stock empfiehlt es sich die Waage auszuschalten (Checkbox im Waagendialog), zur Kalibrierung muß sie eingeschaltet bleiben.

## Diagrammanzeige

Um das Diagramm periodisch (z.Z. ist stündlich voreingestellt) auf einen Webserver (z.B. auf die Homepage des Imkers) zu übertragen ist das 
entsprechende Ziel im Format ```ftp://<user>:<password>@<server>/<verzeichnis>/<hive>.svg``` einzutragen.

Die Übertragung wird deaktiviert wenn das Zielfeld leer ist.

## Archivierung

Da die Anzeige nur einen vorkonfigurerten Zeitraum (z.Z. 1 Woche) zeigt können die Daten periodisch (z.Z. ist wöchentlich voreingestellt) auf einem Zielserver 
(z.B. in ein nicht öffentliches Verzeichnis auf der Homepage) gespeichert werden.```ftp://<user>:<password>@<server>/<verzeichnis>/<hive>-<date>.log```.
<date> wird dieses vor dem Speichern durchs aktuelle Datum (yyddmm-HHMM) ersetzt um ein Überschreiben zu verhindern.  

Achtung: Auch beim Einschalten werden die aktuell vorhandenen Daten hochgeladen und es beginnt ein neues Intervall.

Die Übertragung wird deaktiviert wenn das Zielfeld leer ist.  

## Nachverarbeitung

Mittels Java-Anwendung PiHiveChart (dafür muß Java am PC installiert sein) können die Daten bearbeitet werden, z.B. aus den Archivdaten einen Jahresverlauf erstellen,
Fehlmesswerte (z.B. Waage nicht ausgeschaltet während Arbeiten am Stock) enfernen oder Kanäle mit Wetterdaten löschen.
