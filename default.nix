let
  stable   = import (fetchTarball https://nixos.org/channels/nixos-25.11/nixexprs.tar.xz) {
    config.allowUnfree = true;
   };
  unstable = import (fetchTarball https://nixos.org/channels/nixos-unstable/nixexprs.tar.xz) { };
in stable.mkShell {
   name = "sotohp-env-shell";
   buildInputs =
     let
        jdk17 = stable.jdk17.override {
        };
        scl17 = unstable.scala-cli.override {
          jre = jdk17;
        };
        sbt17 = stable.sbt.override {
          jre = jdk17;
        };
        mvn17 = stable.maven.override {
          jdk_headless = jdk17;
        };
        mill17 = unstable.mill.override {
          jre = jdk17;
        };
        jdk21 = stable.jdk21.override {
        };
        scl21 = unstable.scala-cli.override {
          jre = jdk21;
        };
        sbt21 = stable.sbt.override {
          jre = jdk21;
        };
        mvn21 = stable.maven.override {
          jdk_headless = jdk21;
        };
        mill21 = unstable.mill.override {
          jre = jdk21;
        };
        jdk25 = stable.jdk25.override {
        };
        scl25 = unstable.scala-cli.override {
          jre = jdk25;
        };
        sbt25 = stable.sbt.override {
          jre = jdk25;
        };
        mvn25 = stable.maven.override {
          jdk_headless = jdk25;
        };
        mill25 = stable.mill.override {
          jre = jdk25;
        };
     in with stable; [
       gtk3 pkg-config
       git gitRepo gnupg autoconf curl
       procps gnumake util-linux m4 gperf unzip
       ncurses5 stdenv.cc binutils
       jdk21 mill21 scl21
  ];
}
