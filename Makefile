all:

api:
	sbt "; project api; runMain fr.janalyse.sotohp.api.ApiApp"

viewer:
	sbt "; project gui ; runMain fr.janalyse.sotohp.gui.PhotoViewerApp"

stats:
	sbt "; project cli ; runMain fr.janalyse.sotohp.cli.Statistics"

