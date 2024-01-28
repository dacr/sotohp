## summary : nix-shell environment for CUDA and DeepJavaLibrary
## keywords : nix, djl, nix-shell, @testable
## publish : gist
## authors : David Crosson
## license : Apache NON-AI License Version 2.0 (https://raw.githubusercontent.com/non-ai-licenses/non-ai-licenses/main/NON-AI-APACHE2)
## id : d84d6a0d-e5d2-4a42-aed4-d785231ef7fe
## created-on : 2024-01-27T15:49:43+01:00
## managed-by : https://github.com/dacr/code-examples-manager
## run-with : nix-shell $file

# Run with `nix-shell nix-shell-cuda.nix`

{ pkgs ? import <nixpkgs> {} }:
pkgs.mkShell {
   name = "cuda-env-shell";
   buildInputs = with pkgs; [
     git gitRepo gnupg autoconf curl
     procps gnumake util-linux m4 gperf unzip
     cudatoolkit linuxPackages.nvidia_x11
     cudaPackages.cuda_cudart
     libGLU libGL
     xorg.libXi xorg.libXmu freeglut
     xorg.libXext xorg.libX11 xorg.libXv xorg.libXrandr zlib 
     ncurses5 stdenv.cc binutils
   ];
   shellHook = ''
      export CUDA_PATH=${pkgs.cudatoolkit}
      export LD_LIBRARY_PATH=${pkgs.linuxPackages.nvidia_x11}/lib:${pkgs.ncurses5}/lib:${pkgs.cudaPackages.cuda_cudart}/lib:$LD_LIBRARY_PATH
      export EXTRA_LDFLAGS="-L/lib -L${pkgs.linuxPackages.nvidia_x11}/lib"
      export EXTRA_CCFLAGS="-I/usr/include"
   '';
}
