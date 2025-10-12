let
  stable   = import (fetchTarball https://nixos.org/channels/nixos-25.05/nixexprs.tar.xz) {
    config.allowUnfree = true;
   };
  unstable = import (fetchTarball https://nixos.org/channels/nixos-unstable/nixexprs.tar.xz) { };
in stable.mkShell {
   name = "sotohp-env-shell";
   buildInputs =
     let
       jdk21fx = stable.jdk21.override {
         enableJavaFX = true;
       };
       mill21fx = unstable.mill.override {
         jre = jdk21fx;
       };
     in with stable; [
         gtk3 pkg-config
         git gitRepo gnupg autoconf curl
         procps gnumake util-linux m4 gperf unzip
         ncurses5 stdenv.cc binutils
         jdk21fx mill21fx
  ];
}
