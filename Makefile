all:

sync:
	sbt "; project userInterfacesCLI ; runMain fr.janalyse.sotohp.cli.Synchronize"

process:
	sbt "; project userInterfacesCLI ; runMain fr.janalyse.sotohp.cli.SynchronizeAndProcess"

stats:
	sbt "; project userInterfacesCLI ; runMain fr.janalyse.sotohp.cli.Statistics"

viewer:
	sbt "; project userInterfacesGUI ; runMain fr.janalyse.sotohp.gui.PhotoViewerApp"
