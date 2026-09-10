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

# One-shot backfill of the whole-image feature vectors (embeddings) used for similar-photo search.
# Fills in the missing whole-image feature vectors. ARGS="--force" recomputes the stored ones too,
# ARGS="--rotated-only" restricts the pass to photos the user rotated by hand; combined
# (ARGS="--force --rotated-only") they repair vectors computed before the embedding honoured the
# effective rotation. Rebuild the clusters afterwards - they derive from these vectors.
run-compute-media-features:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.ComputeMediaFeatures $(ARGS)

# (Re)build the clusters of visually similar photos. Pass e.g. ARGS="--radius=0.16 --minPts=3".
run-media-features-clustering:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.MediaFeaturesClustering $(ARGS)

# Backfill image-to-text captions ("auto descriptions"). Needs `ollama serve` + a vision model
# (`ollama pull qwen2.5vl:3b`) and sotohp.processors.captioner.enabled=true.
# A vision model costs seconds per photo, so the whole collection is a multi-day run - use the
# selection flags for an affordable first pass. Photos are visited oldest first.
# Stopping and relaunching is cheap: a photo the model has already been run on is skipped outright,
# caption or not, with no GPU time and no search-engine write.
#   ARGS="--force"        recaption everything, including photos already done
#   ARGS="--starred"      starred photos only
#   ARGS="--since=2020"   taken on/after that date (YYYY or YYYY-MM-DD)
#   ARGS="--limit=5000"   stop after N photos actually captioned (skipped ones don't count)
#   ARGS="--no-index"     leave the search engine alone (needs a later run-search-reindex)
# Each newly captioned photo is re-published to Elasticsearch as it goes, so the index stays in step
# with a multi-day run - no run-search-reindex needed afterwards unless --no-index was used.
run-compute-captions:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.ComputeCaptions $(ARGS)

# Download the GeoNames dump used by the offline reverse-geocoder into .sotohp/geonames/
# (cities500: ~200k populated places; admin1 names; country names). GeoNames data is CC BY 4.0.
download-geonames:
	mkdir -p .sotohp/geonames
	cd .sotohp/geonames && curl -fsSL -O https://download.geonames.org/export/dump/cities500.zip && unzip -o cities500.zip && rm cities500.zip
	cd .sotohp/geonames && curl -fsSL -O https://download.geonames.org/export/dump/admin1CodesASCII.txt
	cd .sotohp/geonames && curl -fsSL -O https://download.geonames.org/export/dump/countryInfo.txt

# One-shot backfill of the textual place (town/region/country) deduced from each photo's GPS point
# by offline reverse-geocoding. Needs the GeoNames dump (make download-geonames). ARGS="--force"
# recomputes stored deductions too. Run 'make run-reindex' afterwards to make places searchable.
run-compute-places:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.ComputePlaces $(ARGS)

run-gps-fix:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.GpsLocationFix

# Checks user-rotated photos for face data computed against the wrong rotation: boxes stuck on the
# pre-rotation frame (needs the detector, slow) and crops cut at a different rotation than the box
# they belong to (pure arithmetic on the JPEG header, instant).
# Report-only by default. ARGS="--fix" applies the repairs, ARGS="--crops-only" skips the slow box
# stage, and they combine: ARGS="--crops-only --fix".
# Stop the API server before running with --fix: both processes open the same LMDB environment, and
# writing from here while it serves is asking for trouble.
run-face-orientation-audit:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.FaceOrientationAudit $(ARGS)

run-reindex:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.Reindex

# Re-publish every media to Elasticsearch. Needed after a SaoMedia schema change or a bulk
# enrichment backfill (e.g. after run-compute-places). 'run-reindex' rebuilds LMDB indexes only.
run-search-reindex:
	mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.SearchReindex

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

