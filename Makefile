all: test

run-api: ui
	mill user-interfaces.api.run

run-viewer:
	mill user-interfaces.gui.run

run-stats:
	mill user-interfaces.cli.runMain fr.janalyse.sotohp.cli.Statistics

assembly:
	mill -i user-interfaces.api.assembly

test: ui
	export PHOTOS_ELASTIC_ENABLED=false && \
	  export PHOTOS_FILE_SYSTEM_SEARCH_LOCK_DIRECTORY="" && \
      mill __.test

docker-build: ui assembly
	nix-build docker.nix
	docker load < result
	docker tag sotohp:latest dacr/sotohp:$$(mill show user-interfaces.api.publishVersion 2>/dev/null | tr -d '"' | tr "-" "_")
	docker tag sotohp:latest dacr/sotohp:latest

docker-push: docker-build
	docker push -a dacr/sotohp

docker-run-demo: docker-build
	docker run --rm -it -p 8888:8080 -v "${PWD}/demo/ALBUMS:/data/ALBUMS" --name sotohp sotohp:latest

docker-run-demo-update: docker-build
	docker run --rm -it -p 8888:8080 \
		-v "${PWD}/demo/ALBUMS:/data/ALBUMS" \
		-v "${PWD}/demo/SOTOHP:/data/SOTOHP" \
		--name sotohp \
		sotohp:latest

# -----------------------------------------------------------------------------
# Publishing helpers
# -----------------------------------------------------------------------------

publish: ui
	@echo "[Sonatype] Uploading bundle and releasing via Central Portal"
	mill mill.javalib.SonatypeCentralPublishModule/

# -----------------------------------------------------------------------------
# Frontend UI build
# Sources live in frontend-user-interface and are built/copied into
# frontend-user-interface-dist which is served by the API under /ui
# -----------------------------------------------------------------------------

UI_SRC := frontend-user-interface
UI_DIST := frontend-user-interface-dist

.PHONY: ui ui-openapi ui-clean ui-ts

ui: ui-openapi ui-ts
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

ui-ts:
	@echo "[UI] Compiling TypeScript (if configured)"
	@if [ -f $(UI_SRC)/package.json ]; then \
		echo "[UI] Running npm ci and build in $(UI_SRC)"; \
		(cd $(UI_SRC) && npm ci --silent || npm install --silent); \
		(cd $(UI_SRC) && npm run -s build); \
	elif [ -f $(UI_SRC)/tsconfig.json ]; then \
		if command -v tsc >/dev/null 2>&1; then \
			(cd $(UI_SRC) && tsc -p tsconfig.json); \
		else \
			echo "[UI] Warning: TypeScript compiler not found; skipping TS compile."; \
		fi; \
	else \
		echo "[UI] No TypeScript config detected; skipping TS compile."; \
	fi

ui-clean:
	rm -rf $(UI_DIST)

