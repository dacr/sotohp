{ pkgs ? import <nixpkgs> {
     config.allowUnfree = true;
  }
}: pkgs.mkShell {
   name = "sotohp-env-shell";
   buildInputs =
     let
       jdk21fx = pkgs.jdk21.override {
         enableJavaFX = true;
       };
       sbt21fx = pkgs.sbt.override {
         jre = jdk21fx;
       };
     in with pkgs; [
         gtk3 pkg-config
         git gitRepo gnupg autoconf curl
         procps gnumake util-linux m4 gperf unzip
         cudatoolkit linuxPackages.nvidia_x11
         cudaPackages.cuda_cudart
         libGLU libGL
         xorg.libXi xorg.libXmu freeglut
         xorg.libXext xorg.libX11 xorg.libXv xorg.libXrandr zlib
         ncurses5 stdenv.cc binutils
         jdk21fx sbt21fx
  ];
  ## TODO workaround as openjfx is not yet well supported under NIX
  shellHook = ''
      export CUDA_PATH=${pkgs.cudatoolkit}
      export LD_LIBRARY_PATH=${pkgs.linuxPackages.nvidia_x11}/lib:$LD_LIBRARY_PATH
      export LD_LIBRARY_PATH=${pkgs.ncurses5}/lib:$LD_LIBRARY_PATH
      export LD_LIBRARY_PATH=${pkgs.cudaPackages.cuda_cudart}/lib:$LD_LIBRARY_PATH
      export EXTRA_LDFLAGS="-L/lib -L${pkgs.linuxPackages.nvidia_x11}/lib"
      export EXTRA_CCFLAGS="-I/usr/include"
  '';
}
