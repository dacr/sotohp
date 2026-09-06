all: test

run: run-api

run-api: ui
	mill --no-server user-interfaces.api.run

openapi-spec:
	mill --no-server user-interfaces.api.run --just-generate-openapi-specs docs/sotohp-api-docs.json

run-face-inference:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.FaceInference

run-face-inference-evaluate:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.FaceInferenceEvaluate

run-face-fix:
	mill user-interfaces.cli.runMain fr.janalyse.sotohp.cli.FacesFix

# Clear the inference leftovers (inferred person/confidence/timestamp/ignore) of faces identified by a human.
run-face-inferred-fields-fix:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.FaceInferredFieldsFix

run-stats:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.Statistics

run-gps-fix:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.GpsLocationFix

# Report-only by default; pass ARGS="--fix" to actually remap faces stuck on their pre-rotation frame.
run-face-orientation-audit:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.FaceOrientationAudit $(ARGS)

run-reindex:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.Reindex

run-google-photos-sync:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.GooglePhotosSync

run-google-photos-sync-test:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.GooglePhotosSync --portfolio="Photos de rue" --execute

run-portfolio-video:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.PortfolioVideoGenerator

run-portfolio-video-test:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.PortfolioVideoGenerator --portfolio="Photos de rue" --output=/tmp/sotohp-videos

check-updates:
	mill mill.javalib.Dependency/showUpdates

bsp-install:
	mill --bsp-install

api-jar:
	mill -i user-interfaces.api.jar

api-universal-stage:
	mill -i user-interfaces.api.universalStage

test: ui
	export PHOTOS_ELASTIC_ENABLED=false && \
	  export PHOTOS_FILE_SYSTEM_SEARCH_LOCK_DIRECTORY="" && \
      mill __.test

docker-build: ui api-universal-stage
	nix-build docker.nix
	docker load < result
	docker tag sotohp:latest dacr/sotohp:$$(mill show user-interfaces.api.publishVersion 2>/dev/null | tr -d '"' | tr "-" "_")
	docker tag sotohp:latest dacr/sotohp:latest

docker-demo-build: ui api-universal-stage
	nix-build docker_demo.nix
	docker load < result
	docker tag sotohp_demo:latest dacr/sotohp_demo:$$(mill show user-interfaces.api.publishVersion 2>/dev/null | tr -d '"' | tr "-" "_")
	docker tag sotohp_demo:latest dacr/sotohp_demo:latest

docker-push: docker-build
	docker push -a dacr/sotohp

docker-demo-push: docker-demo-build
	docker push -a dacr/sotohp_demo

docker-run-demo: docker-demo-build
	docker run --rm -it -p 8888:8080 --name sotohp_demo dacr/sotohp_demo:latest


docker-run-demo-maker: docker-build
	docker run --rm -it -p 8888:8080 -v "${PWD}/demo/ALBUMS:/data/ALBUMS" --name sotohp dacr/sotohp:latest

docker-run-demo-maker-update: docker-build
	docker run --rm -it -p 8888:8080 \
		-v "${PWD}/demo/ALBUMS:/data/ALBUMS" \
		-v "${PWD}/demo/SOTOHP:/data/SOTOHP" \
		--name sotohp \
		dacr/sotohp:latest

keycloak-local:
	docker compose up -d keycloak


# -----------------------------------------------------------------------------
# Publishing helpers
# -----------------------------------------------------------------------------

publish: ui
	@echo "[Sonatype] Uploading bundle and releasing via Central Portal"
	mill mill.javalib.SonatypeCentralPublishModule/

# -----------------------------------------------------------------------------
# Frontend UI build
# Sources live in frontend-user-interface and are built/copied into
# frontend-user-interface-dist which is served by the API at the root path /
# -----------------------------------------------------------------------------

UI_SRC := frontend-user-interface
UI_DIST := frontend-user-interface-dist

.PHONY: ui ui-openapi ui-clean

# The UI is a Next.js app (static export, output: 'export' — see next.config.ts): `npm run build`
# regenerates lib/api-types.ts from the OpenAPI spec (prebuild hook) and emits plain HTML/JS/CSS
# into $(UI_SRC)/out, which is then copied verbatim into $(UI_DIST) for the API to serve.
ui: ui-openapi
	@echo "[UI] Building Next.js static export"
	(cd $(UI_SRC) && npm ci --silent || npm install --silent)
	(cd $(UI_SRC) && npm run build)
	@echo "[UI] Copying into $(UI_DIST)"
	rm -rf $(UI_DIST)
	mkdir -p $(UI_DIST)
	cp -r $(UI_SRC)/out/. $(UI_DIST)/
	@echo "[UI] Done. Serve at http://127.0.0.1:8080/ (after 'make api')"

ui-openapi: openapi-spec
	@echo "[UI] OpenAPI spec generated (docs/sotohp-api-docs.json)"

ui-clean:
	rm -rf $(UI_DIST) $(UI_SRC)/out $(UI_SRC)/.next

