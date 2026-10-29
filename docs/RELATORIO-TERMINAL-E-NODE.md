# Terminal + Node.js: o que foi corrigido, o que foi testado e o que ainda não é possível

Documento técnico desta rodada de correções no NodeSphere IDE. Serve também como
guia de validação no aparelho e como contexto para quem for continuar o trabalho.

---

## 1. Os quatro pedidos do prompt, item por item

### 1.1 Node.js com binário compatível com Bionic/Android — **feito**

| Antes | Agora |
| --- | --- |
| Downloader aceitava o `.tar.gz` de nodejs.org (glibc) e o `node` morria com `No such file or directory` | `TermuxPackages.kt` instala o pacote oficial do Termux: índice apt → `.deb` → `data.tar.xz` → prefixo do app |

Como funciona (`app/src/main/java/com/mobile/nodeide/TermuxPackages.kt`):

1. `architecture()` traduz a ABI do aparelho para o nome do apt
   (`arm64-v8a` → `aarch64`, `armeabi-v7a` → `arm`, `x86_64` → `x86_64`, `x86` → `i686`).
2. `loadIndex()` baixa
   `https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-<arch>/Packages.xz`
   (tenta `.xz`, depois `.gz`, depois texto puro; espelho `packages-cf.termux.dev` como reserva).
3. `parseIndex()` lê o formato do `dpkg` (inclusive `Depends` com continuação de linha,
   `a | b` e `nome (>= versão)`).
4. `closure()` resolve a árvore de dependências a partir de `nodejs`
   (`libc++`, `libandroid-support`, `zlib`, `openssl`, `c-ares`, …) e instala as
   dependências primeiro.
5. `extract()` mapeia `data/data/com.termux/files/usr/<resto>` →
   `files/usr/<resto>`, recria links simbólicos e aplica permissões.
6. `fixRunPaths()` + `ElfPatcher.kt` reescrevem o `DT_RUNPATH` dos binários.

**Permissão de execução**: além de `file.setExecutable(true, false)` em
`NodeRuntime.makeExecutable`/`TermuxPackages.permissions`, os scripts `npm`/`npx`
são gerados por `script()` com `#!/system/bin/sh` e o mesmo `setExecutable`.

**Assets do APK**: `NodeRuntime.installFromAssets()` varre `assets/node/` e aceita
`.deb` do Termux, `.zip` e `.tar.gz`; o `MainActivity.bootstrapRuntime()` tenta
`jniLibs` e depois `assets`. Ou seja, os dois caminhos sugeridos no prompt
(assets do APK **ou** repositório do Termux) existem.

**Diagnóstico de glibc**: `inspectElf()` agora extrai o **`PT_INTERP`** e
`installBinary()` recusa explicitamente binário glibc, apontando o caminho certo —
em vez de deixar o erro `No such file or directory` aparecer no terminal.

### 1.2 Diretório de trabalho (CWD) e comandos `ls`/`node`/`npm` — **feito**

- `TerminalSession.openShell()` faz `ProcessBuilder(SHELL).directory(directory)`,
  com `directory` inicial = **pasta do projeto aberto** (`IdeFragment` passa
  `startDirectory = project`). Antes o shell abria em `files/home`, e era por isso
  que `ls` aparecia vazio.
- `ensureDirectory(projeto)` roda `cd <projeto>` antes de `Executar` e `npm install`.
- O `cwd` continua sendo rastreado por um marcador interno
  (`printf '__NODESPHERE_CWD__%s %s\n' "$PWD" "$$"`), então `cd` digitado à mão
  também é acompanhado — e o PID do shell vem junto, para matar a árvore de
  processos no `Parar`/`^C`.
- `NodeRuntime.environment()` monta:
  `PATH = files/usr/bin:<PATH do sistema>`, `HOME = files/home`,
  `TMPDIR = files/usr/tmp`, `NODE_PATH = files/usr/lib/node_modules`,
  mais `PREFIX`, `LD_LIBRARY_PATH`, `PWD`, `SHELL`, `TERM`,
  `NPM_CONFIG_CACHE/PREFIX/TMP/USERCONFIG` e `NODE_REPL_HISTORY`.

### 1.3 Terminal interativo na tela, sem caixa de texto — **feito no que é visível; PTY nativo continua impossível aqui**

Saiu o `EditText` + `TextView` do rodapé. Entraram três arquivos novos:

| Arquivo | Papel |
| --- | --- |
| `TerminalEmulator.kt` | grade de caracteres, cursor, `\r`/`\n`/`\b`/`\t`, SGR (16/256/truecolor, negrito, sublinhado, reverso), `CSI H/f/A/B/C/D/G/d/J/K/L/M/P/@/X/S/T`, `ESC 7/8/M/D/E/c`, `OSC` (título), scrollback de 2000 linhas |
| `TerminalView.kt` | desenha a grade, cursor piscando, rolagem por arraste, toque = foco + teclado, `onCreateInputConnection` (texto direto do teclado, sem campo), teclas físicas (Enter, setas, Backspace, Tab, Ctrl+letra) |
| `TerminalSession.kt` | `/system/bin/sh` real em pipes **no diretório do projeto**, eco local, edição de linha (Backspace, `←`/`→`, Home/End), histórico `↑`/`↓`, `^C`, `^D`, `^L`, fila de comandos e `help`/`runtime`/`clear`/`exit` |

O que o usuário vê: teclado do sistema digitando **dentro** do terminal, letras
aparecendo (eco), cores, prompt `nodesphere:projeto$`, rolagem do histórico,
teclas de atalho na barra do painel.

O que **não** existe: `/dev/tty`, `SIGINT` real, `SIGWINCH`, `vim`/`htop`,
`readline` com Tab-completion do shell. Ver seção 4.

### 1.4 `targetSdk = 28` e permissões — **mantido**

`app/build.gradle.kts` continua com `targetSdk = 28` (regra W^X do Android 10+),
`compileSdk = 36`, `minSdk = 26`, `extractNativeLibs = "true"` e
`useLegacyPackaging = true`. Nada foi alterado nesse ponto.

---

## 2. O que foi realmente verificado nesta sessão (com evidência)

O ambiente aqui não tem Android SDK nem JVM, então **não foi possível compilar o
APK**. Para não entregar código "no escuro", os algoritmos novos foram validados
em Python, replicando linha a linha a mesma aritmética, contra arquivos reais:

1. **Reescrita do `DT_RUNPATH` (`ElfPatcher`)** — compilado um binário real com
   `gcc -Wl,-rpath,/data/data/com.termux/files/usr/lib -Wl,--enable-new-dtags`,
   aplicado o patch e conferido com `readelf`:
   `RUNPATH: [$ORIGIN/../lib]`; o binário continuou executando (`exit=0`).
2. **Leitura de `.deb` + `data.tar.xz` + tar + mapeamento do prefixo** — gerado um
   `.deb` com `dpkg-deb` contendo `data/data/com.termux/files/usr/{bin,lib,share}`,
   `npm` como symlink e uma manpage. Resultado: `bin/node` com `0755`, symlink
   recriado, `share/man` ignorado, caminhos mapeados corretamente.
3. **Fatiamento do marcador de CWD/PID** — 339 combinações de tamanho de chunk
   (1, 3 e 7 caracteres) sobre uma saída realista: nenhum pedaço do marcador vazou
   para a tela, `cwd` e PID sempre corretos.
4. **Índice `Packages` + resolução de dependências** — índice sintético com
   `Depends` multi-linha, alternativas, versões e `bash` (ignorado): árvore
   resolvida na ordem certa, com `nodejs` por último.
5. **Balanceamento sintático** de todos os `.kt` (parênteses/chaves/colchetes fora
   de strings e comentários) — sem erros.

O que isso **não** prova: que o projeto compila (faltam as dependências Android e
o Gradle resolver), nem que o `node` do Termux executa no seu aparelho (ver 4.2).

---

## 3. Como validar no aparelho (roteiro)

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Abra um projeto → **⌘ Terminal**. Esperado:
   ```
   ╭─ NodeSphere terminal  /data/.../files/projects/meu-app
   │ PREFIX /data/.../files/usr
   │ node   não instalado
   nodesphere:meu-app$
   ```
2. Digite `ls` → os arquivos do projeto aparecem (era o sintoma do CWD errado).
3. `cd ..` → o prompt muda para o diretório pai.
4. *Plugins → Node.js → Instalar do Termux* → acompanhe o progresso
   (`(1/7) libc++ …`, `(7/7) nodejs …`), depois volte ao terminal:
   `node -v`, `npm -v`, `which node` (se existir) / `echo $PATH`.
5. **▶ Executar** com `server.js` presente → o servidor sobe no terminal e a saída
   aparece colorida; **■ Parar** derruba o shell **e** o `node` filho.
6. `runtime` → mostra ABI, versão, **interpretador ELF** (tem que começar com
   `/system/bin/linker64`), `PATH`, `HOME`, `TMPDIR`, `NODE_PATH`.
7. Se algo falhar, `runtime` + `logcat -s NodeSphere` (o instalador registra
   `RUNPATH ajustado: …` e cada pacote ignorado).

---

## 4. O que **não** foi possível fazer aqui — e por quê

### 4.1 Compilar/rodar o app neste sandbox
Sem Android SDK (`sdkmanager`/`android.jar`) e sem JVM, `./gradlew assembleDebug`
não roda. Além disso a saída de rede é restrita a alguns hosts (GitHub, npm, PyPI);
artefatos de JDK/Kotlin no GitHub Releases redirecionam para
`objects.githubusercontent.com`, que é bloqueado. Portanto: **nenhum build foi
executado** nesta sessão — só análise estática e os testes descritos na seção 2.

### 4.2 Baixar/testar os pacotes reais do Termux daqui
`packages.termux.dev` não está na lista de hosts permitidos. O downloader foi
escrito conforme o layout apt documentado e testado com um `.deb` equivalente
gerado localmente, mas **a execução real do `node` do Termux não pôde ser
exercitada**. Pontos de atenção no aparelho:

- **RUNPATH**: os binários do Termux procuram libs em
  `/data/data/com.termux/files/usr/lib`. Por isso `ElfPatcher` reescreve o
  `DT_RUNPATH` para `$ORIGIN/../lib` (e o `LD_LIBRARY_PATH` também é exportado).
  Se o Android recusar `$ORIGIN` no seu aparelho, o sintoma será
  `library "libc++_shared.so" not found`; a mitigação é instalar as libs também em
  `files/usr/lib` (já é o que o instalador faz) e/ou usar a rota `assets/`.
- **Linker namespace**: `targetSdk = 28` mantém a execução permitida em
  `files/`; isso é o mesmo que o Termux faz. Não testado em aparelhos específicos
  (Android 14/15 são os mais rígidos).
- **Compressão**: o extrator aceita `data.tar.xz` (padrão do `dpkg-deb`, usado
  pelo Termux), `data.tar.gz` e `data.tar`. Se algum espelho publicar
  `data.tar.zst`, o app falha com mensagem clara (`Compactação não suportada`) —
  adicionar `com.github.luben:zstd-jni` resolveria.

### 4.3 PTY de verdade (o item mais "impossível" do prompt)
O prompt pedia para substituir a implementação por `termux-view` /
`jackpal.androidterm` / `libtermux-exec` "abrindo a chamada de sistema do shell
local". O problema técnico:

- **`termux-view` não é um terminal**: é só a `View` (o desenho). O PTY fica em
  `termux-app`/`terminal-emulator`, que usa **código nativo** (`JNI` → `fork`,
  `posix_openpt`, `grantpt`, `unlockpt`, `setsid`, `TIOCSCTTY`, `execve`).
  A biblioteca `jackpal.androidterm` (Termux original) é exatamente isso: um
  `.jar` + `.so` que precisa ser compilado para cada ABI com NDK.
- **Bionic não tem `forkpty`**, e a API pública do Android não expõe
  `posix_openpt`/`ioctl` (`android.system.Os.ioctlInt` é `@SystemApi`/`@hide`,
  e `/dev/ptmx` não ajuda sem `TIOCGPTN`). Ou seja: **não existe PTY em Kotlin
  puro**. Não há artifact de PTY no Maven Central.
- Compilar NDK aqui era impossível (sem NDK) e entregar código C/JNI não
  compilado, que só liga quando o usuário mexe no Gradle, seria pior do que não
  entregar.
- Não há, no repositório do Termux nem no nodejs-mobile, um **executável** `node`
  pronto: o nodejs-mobile publica só `libnode.so` (biblioteca de embedding, sem
  `main`). As releases verificadas nesta sessão (`nodejs-mobile v18.20.4`,
  57 MB, `bin/<abi>/libnode.so` + `include/node`) confirmam isso. O "Node 24
  para Android" citado em alguns textos (fork `digidem/nodejs-mobile-android`)
  não existe nesse caminho: a API do GitHub devolve 404.

**Consequência prática**: o terminal emulado cobre o que o prompt pediu em termos
de experiência (eco, ANSI, cores, comandos diretos, sem caixa separada), mas
`vim`, `htop`, `less` interativo, Tab-completion do shell, `Ctrl+C` por sinal e
`SIGWINCH` continuam fora — e continuarão fora até existir NDK no build.

Se quiserem fechar essa lacuna depois, o caminho é:

```
app/src/main/cpp/pty.c        posix_openpt → grantpt → unlockpt → ptsname
                              fork → setsid → ioctl(TIOCSCTTY) → dup2(slave)
                              tcsetattr(termios em modo raw) → execve("/system/bin/sh")
app/src/main/cpp/CMakeLists.txt
app/build.gradle.kts          externalNativeBuild { cmake { path ... } } + ndk abiFilters
Kotlin                        Pty.open(cols, rows, env, cwd) → FileDescriptor master
                              TerminalSession passa a ler/escrever no master e resize → TIOCSWINSZ
```

Com isso o `TerminalEmulator`/`TerminalView` atuais continuam válidos (eles só
consomem bytes ANSI); troca-se apenas a origem dos bytes.

### 4.4 Outros limites conhecidos deste desenho
- Redimensionar o painel muda a emulação na tela, mas `COLUMNS`/`LINES` só valem
  no próximo `openShell()` (sem tty não há `SIGWINCH`).
- Texto digitado durante um comando em execução fica no buffer e é reexibido no
  próximo prompt (não é escrito no meio da saída do programa).
- `^C` reinicia o shell no mesmo diretório; agora ele também mata os filhos
  lendo `/proc/<pid>/task/<pid>/children` (o `node server.js` não fica órfão).
- O histórico da tela vive na `View`: rotacionar a tela recria a sessão.

---

## 5. Arquivos tocados

```
app/src/main/java/com/mobile/nodeide/
  TermuxPackages.kt   (novo)  instalador do Termux (índice, deps, .deb)
  Archives.kt         (novo)  leitor de .deb/ar/tar/xz
  ElfPatcher.kt       (novo)  RUNPATH → $ORIGIN/../lib
  TerminalEmulator.kt (novo)  emulação de terminal (ANSI, cursor, scrollback)
  TerminalView.kt     (novo)  desenho + teclado na tela
  TerminalSession.kt  (novo)  shell no diretório do projeto, eco, histórico
  ShellSession.kt     (removido)
  Ansi.kt             (removido — substituído pelo TerminalEmulator)
  NodeRuntime.kt      (alt)   ambiente, ELF/PT_INTERP, assets, Termux, launchers
  IdeFragment.kt      (alt)   terminal novo, Executar/npm no projeto, teclas
  PluginsFragment.kt  (alt)   "Instalar do Termux", importar .deb, assets
  MainActivity.kt     (alt)   bootstrap tenta jniLibs e assets
app/src/main/res/layout/fragment_ide.xml   (alt)  TerminalView no lugar de EditText/TextView
app/build.gradle.kts                       (alt)  org.tukaani:xz:1.9
app/src/main/assets/node/README.md         (novo) rota offline
README.md, app/src/main/jniLibs/README.md  (alt)
```
