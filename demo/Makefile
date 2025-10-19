all: prepare

prepare:
	docker run \
      --rm \
	  -p 8888:8080 \
	  -v "$$(pwd)/ALBUMS:/data/ALBUMS" \
	  -v "$$(pwd)/SOTOHP:/data/SOTOHP" \
	  --name sotohp \
	  dacr/sotohp:latest
