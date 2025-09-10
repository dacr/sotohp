all:

api:
	sbt "; project api; runMain fr.janalyse.sotohp.api.ApiApp"

viewer:
	sbt "; project gui ; runMain fr.janalyse.sotohp.gui.PhotoViewerApp"

stats:
	sbt "; project cli ; runMain fr.janalyse.sotohp.cli.Statistics"

# -----------------------------------------------------------------------------
# Frontend UI build
# Sources live in frontend-user-interface and are built/copied into
# frontend-user-interface-dist which is served by the API under /ui
# -----------------------------------------------------------------------------

UI_SRC := frontend-user-interface
UI_DIST := frontend-user-interface-dist

.PHONY: ui ui-openapi ui-clean

ui: ui-openapi
	@echo "[UI] Building UI into $(UI_DIST)"
	rm -rf $(UI_DIST)
	mkdir -p $(UI_DIST)/assets
	cp $(UI_SRC)/index.html $(UI_DIST)/
	cp $(UI_SRC)/favicon.svg $(UI_DIST)/
	cp -r $(UI_SRC)/assets/* $(UI_DIST)/assets/ 2>/dev/null || true
	@echo "[UI] Done. Serve at http://127.0.0.1:8080/ui/ (after 'make api')"

ui-openapi:
	@echo "[UI] Fetching OpenAPI spec"
	mkdir -p $(UI_SRC)/openapi
	curl -fsSL http://127.0.0.1:8080/docs/docs.yaml -o $(UI_SRC)/openapi/docs.yaml || true
	@echo "[UI] OpenAPI spec fetch attempted (ignored errors if API not running)"

ui-clean:
	rm -rf $(UI_DIST)

