# Model schemas: the build of a model a host runs, by its hardware, with the facets that hold, fetch and execute it.
# mlnix only: fsReadLink and procRun are its primops, not Nix's.
#
#   mlnix eval --json utils/mlnix/model.nix -A jeva     this host's TRIKESHED_JEVA_* settings (JevaConfig.host), or its refusal
#   mlnix eval --json utils/mlnix/model.nix -A model    the schema of this host's hardware
#   mlnix eval --json utils/mlnix/model.nix -A verify   every held file against its sha256
#   mlnix eval --raw  utils/mlnix/model.nix -A aria2    an aria2c --input-file that fetches the schema's files into cache[0]
#   mlnix eval --json utils/mlnix/model.nix -A model --arg hardware '{ system = "x86_64-linux"; accelerator = "cuda"; }'
{ hardware ? null, checkout ? toString ../.. }:
let
  inherit (builtins) all concatStringsSep filter fromJSON fsReadLink getEnv hashFile head isNull pathExists procRun replaceStrings;

  # -- hardware: what a schema is chosen by ---------------------------------------------------------------------------
  nvidia = filter pathExists [ "/usr/lib/wsl/lib/nvidia-smi" "/usr/bin/nvidia-smi" ];
  host = rec {
    system = builtins.currentSystem;
    accelerator =
      if system == "aarch64-darwin" then "mlx" # Apple silicon: Metal, through MLX
      else if nvidia != [ ] && (procRun { prog = head nvidia; args = [ "-L" ]; }).status == 0 then "cuda"
      else "cpu";
  };
  hw = if isNull hardware then host else hardware;

  # -- schemas: Jeva-4B's 4-bit builds, one Hugging Face repository at one revision -----------------------------------
  repo = "jnorthrup/jeva-4b-mlx-q4g32-dist";
  revision = "ce2ef2d47cb505c85561e28ce2609ea30e55b968";
  # xet is the file's Xet hash, null where the repository's git holds the file
  file = path: sha256: xet: { inherit path sha256 xet; };
  decision = [
    (file "config.yaml" "4cb8d4924e11062ee978b67b5891eadf90df249013f1878a3ccafcf432c02fd7" null)
    (file "head.pt" "ade5f37b88f876f612d395d2d78eda229c233a744700e88269438c4047116805"
      "b836ade45d3f002592b4845f04260514afdff3a1234e6e2df82f6d9940ca7343")
  ];
  schemas = {
    # affine 4-bit, group 32 (embeddings 8-bit, group 64): what JevaInference.loadDevice reads
    mlx = decision ++ [
      (file "backbone_mlx/config.json" "fcb4c99d1416848efcab0064d767765c043006b0a062fbcc5f2de2ffc1bf725a" null)
      (file "backbone_mlx/model.safetensors" "ac6bf6fe323f98c8bbb3c61fe2e6d64e1b135107477a7052b3c0adc3c3d01df4"
        "478091237edcadaa36324c988be82b2c02371c2a1c84829349468568a4b84aed")
      (file "backbone_mlx/tokenizer.json" "a5cd9732badce41de57e6efce8302930ded1c1188c5f81feb2bd6c24c4a1941f"
        "e558cdad9a88d177076f9525ed4ef2d55dfd5dafafd6baac7c6105590ab49b7b")
    ];
    # Q4_K_M, requantized from the mlx build
    gguf = decision ++ [
      (file "gguf/jeva-4b-calibrated-Q4_K_M.gguf" "ca52d054c4a7d116b836bd42d85d0ee0e321da6d4732a45317084f0e08e46308"
        "b07e8ba762c3c3e735eece9095e479279ddb0ed2da88270414e4b3b9fedabdb9")
    ];
  };
  format = if hw.accelerator == "mlx" then "mlx" else "gguf";
  model = { inherit repo revision format; device = hw.accelerator; files = schemas.${format}; };

  # -- cache: the directories that may hold the files, the guest-module root (GuestModules.root) first ---------------
  name = baseNameOf repo;
  env = variable: fallback: let value = getEnv variable; in if value == "" then fallback else value;
  cache = [
    "${env "TRIKESHED_SUBVM_HOME" "${checkout}/utils/subvm"}/${name}"
    "${env "HF_HUB_CACHE" "${env "HF_HOME" "${getEnv "HOME"}/.cache/huggingface"}/hub"}/models--${replaceStrings [ "/" ] [ "--" ] repo}/snapshots/${revision}"
  ];
  held = filter (dir: all (f: pathExists "${dir}/${f.path}") model.files) cache;
  verify = held != [ ] && all (f: hashFile "sha256" "${head held}/${f.path}" == f.sha256) model.files;

  # -- xet: the content addresses reactor/xet reconstructs by (XetCasProtocol.reconstructionPath); hf fetches by them into cache[1]
  xet = {
    files = map (f: { inherit (f) path; hash = f.xet; }) (filter (f: !isNull f.xet) model.files);
    hf = [ "hf" "download" repo "--revision" revision ] ++ map (f: f.path) model.files;
  };

  # -- aria2: every file by https, checked against its sha256 ---------------------------------------------------------
  aria2 = concatStringsSep "" (map (f: ''
    https://huggingface.co/${repo}/resolve/${revision}/${f.path}
      dir=${head cache}
      out=${f.path}
      checksum=sha-256=${f.sha256}
  '') model.files);

  # -- lib: the library a format executes on, at the version this checkout's code is written against ------------------
  lib = {
    # src/macosMain/resources/META-INF/cinterop/mlx.def
    mlx = rec {
      root = "/opt/homebrew/opt/mlx-c";
      version = "0.8.0";
      installed = let cellar = fsReadLink root; in if isNull cellar then null else baseNameOf cellar;
      dylib = "${root}/lib/libmlxc.dylib";
    };
  };

  # -- jeva: the settings of this host's schema, where this checkout can run it ---------------------------------------
  modified = path:
    let stat = procRun { prog = "/usr/bin/stat"; args = [ "-L" "-f" "%m" path ]; }; in
    if stat.status == 0 then fromJSON stat.stdout else 0;
  # a worker linked before the installed library was built against another one
  worker = filter (path: pathExists path && modified path >= modified lib.mlx.dylib)
    (map (kind: "${checkout}/build/bin/macos/jevaWorker${kind}Executable/jevaWorker.kexe") [ "Release" "Debug" ]);
  jeva =
    if format != "mlx" then { refusal = "no runtime of this checkout reads the ${format} build"; }
    else if lib.mlx.installed != lib.mlx.version then {
      refusal = "src/macosMain is written against mlx-c ${lib.mlx.version}; ${lib.mlx.root} holds ${if isNull lib.mlx.installed then "none" else lib.mlx.installed}";
    }
    else if held == [ ] then { refusal = "no cache holds ${name}: ${concatStringsSep ", " cache}"; }
    else if worker == [ ] then {
      refusal = "no jevaWorker.kexe under ${checkout}/build/bin/macos linked since ${lib.mlx.dylib}: ./gradlew linkJevaWorkerReleaseExecutableMacos";
    }
    else {
      TRIKESHED_JEVA_MODEL = head held;
      TRIKESHED_JEVA_DEVICE = "mlx";
      TRIKESHED_JEVA_RUNTIME = "kotlin";
      TRIKESHED_JEVA_WORKER = head worker;
    };
in
{ hardware = hw; inherit model cache verify xet aria2 lib jeva; }
