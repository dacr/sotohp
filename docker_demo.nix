let
  pkgs = import (fetchTarball "https://nixos.org/channels/nixos-25.05/nixexprs.tar.xz") {};
  # Generate the UTF-8 locale archive inside the image
  localeArchive = pkgs.glibcLocales.override {
    locales = [ "en_US.UTF-8/UTF-8" "en_US.UTF-16/UTF-16"];
  };

  # Ensure the prebuilt artifacts from the repo are copied into the Nix store
  apiStage = pkgs.runCommand "sotohp-api-universal" {} ''
    mkdir -p $out
    cp -r ${./out/user-interfaces/api/universalStage.dest} $out/sotohp-api
  '';

  uiDist = pkgs.runCommand "sotohp-ui-dist" {} ''
    mkdir -p $out
    cp -r ${./frontend-user-interface-dist} $out/frontend-user-interface-dist
  '';

  demoFiles = pkgs.runCommand "sotohp-demo-files" {} ''
    mkdir -p $out
    cp -pr ${./demo} $out/demo
  '';

  runScript = pkgs.writeShellScript "run-sotohp" ''
    set -euo pipefail
    export PHOTOS_LISTENING_PORT="''${PHOTOS_LISTENING_PORT:-8080}"
    # Default directories if not provided by the user
    export PHOTOS_CACHE_DIRECTORY="''${PHOTOS_CACHE_DIRECTORY:-/data/SOTOHP/cache}"
    export PHOTOS_DATABASE_PATH="''${PHOTOS_DATABASE_PATH:-/data/SOTOHP/database}"
    ${pkgs.toybox}/bin/mkdir -p $PHOTOS_CACHE_DIRECTORY
    ${pkgs.toybox}/bin/mkdir -p $PHOTOS_DATABASE_PATH
    # If albums are mounted, you can point PHOTOS_FILE_SYSTEM_SEARCH_LOCK_DIRECTORY to a subdirectory of /data
    export JAVA_OPTS="-Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED"
    export JAVA_OPTS="-Xms1g -Xmx1g $JAVA_OPTS"
    exec bash /app/bin/sotohp-api
  '';

in pkgs.dockerTools.buildLayeredImage {
  name = "sotohp_demo";
  tag = "latest";

  contents = [apiStage uiDist demoFiles localeArchive];

  extraCommands = ''
    mkdir -p app
    ln -s ${apiStage}/sotohp-api/bin app/bin
    ln -s ${apiStage}/sotohp-api/lib app/lib
    ln -s ${uiDist}/frontend-user-interface-dist app/frontend-user-interface-dist
    mkdir -p data
    ln -s  ${demoFiles}/demo/ALBUMS data/ALBUMS
    cp -rp ${demoFiles}/demo/SOTOHP data/
    cp ${runScript} entrypoint.sh
    chmod +x entrypoint.sh
    mkdir -m 1777 -p tmp
  '';

  config = {
    WorkingDir = "/app";
    Entrypoint = [ "${pkgs.bash}/bin/bash" "/entrypoint.sh" ];
    #Entrypoint = [ "${pkgs.bash}/bin/bash" ];
    Env = [
      "PATH=${pkgs.coreutils}/bin:${pkgs.bash}/bin:${pkgs.gawk}/bin:${pkgs.temurin-jre-bin}/bin:$PATH"
      #"PATH=${pkgs.temurin-bin}/bin:$PATH"
      "DJL_CACHE_DIR=/data/DJLAI"
      "PHOTOS_LISTENING_PORT=8080"
      "PHOTOS_FILE_SYSTEM_SEARCH_LOCK_DIRECTORY=/data/ALBUMS"
      "PHOTOS_CACHE_DIRECTORY=/data/SOTOHP/cache"
      "PHOTOS_DATABASE_PATH=/data/SOTOHP/database"
      "PHOTOS_ELASTIC_ENABLED=false"
      "PHOTOS_ELASTIC_URL=http://127.0.0.1:9200"
      "LANG=en_US.UTF-8"
      "LC_ALL=en_US.UTF-8"
      # Point glibc to the included locale archive
      "LOCALE_ARCHIVE=${localeArchive}/lib/locale/locale-archive"        # Required to support more encodings
      "LD_LIBRARY_PATH=${pkgs.lib.makeLibraryPath [pkgs.stdenv.cc.cc]}"  # Required for DJI AI engines
    ];
    ExposedPorts = {
      "8080/tcp" = {};
    };
    Volumes = {
      "/data/ALBUMS" = {};
      "/data/SOTOHP" = {};
    };
  };
}