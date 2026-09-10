{
  description = "AI Coding Environment with ClaudeCode or OpenCode";

  inputs = {
    nixstable.url      = "github:NixOS/nixpkgs/nixos-26.05";
    nixunstable.url    = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url    = "github:numtide/flake-utils";
  };
  outputs = { self, nixstable, nixunstable, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
    let
        stable = import nixstable {
          inherit system;
          config.allowUnfree = true;
        };
        unstable = import nixunstable {
          inherit system;
          config.allowUnfree = true;
        };

      jdk = stable.jdk25;

      sbt = stable.sbt.override {
        jre = jdk;
      };
      scl = unstable.scala-cli.override {
        jre = jdk;
      };
      mvn = stable.maven.override {
        jdk_headless = jdk;
      };
      mill = unstable.mill.override {
        jre = jdk;
      };

      # Local vision model server for image-to-text captions (sotohp.processors.captioner).
      # nixpkgs builds ollama-cuda for compute capability 7.5+ only (Turing and newer), so a
      # Pascal card is skipped at runtime ("compute capability not in compiled architectures")
      # and inference silently falls back to the CPU. Compile for this machine's GPU instead:
      # sm_61 = GTX 10xx. Check yours with `nvidia-smi --query-gpu=compute_cap --format=csv`.
      ollama = unstable.ollama-cuda.override {
        cudaArches = [ "sm_61" ];
      };
    in
    {
        devShells.default = stable.mkShell {
          packages = [
          unstable.opencode      # The AI Agent
          unstable.claude-code

          stable.imagemagick     # For HEIF image processing
          ollama                 # Vision model server for image-to-text captions (see above)

          # Scala Development
          jdk              # Java Runtime
          sbt              # Build Tool
          mill             # Build Tool
          scl              # Build Tool
          stable.scalafmt  # Formatter
          stable.protobuf  # Provides native protoc compiler
        ];

        shellHook = ''
            echo "🤖 Dev Environment Loaded"
        '';
      };
    }
  );
}
