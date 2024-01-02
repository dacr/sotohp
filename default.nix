with import <nixpkgs> {};
stdenv.mkDerivation {
  name = "env";
  buildInputs = [
    gtk3 pkg-config
  ];
  ## TODO workaround as openjfx is not yet well supported under NIX => use a dedicated flake
  shellHook = ''
    export OPENJFX_LIBRARY_PATH=$HOME/javafx-sdk-21/lib/
    export LD_LIBRARY_PATH=/etc/jfx21/modules_libs/javafx.graphics/
  '';
}
