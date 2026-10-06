# Edukio Android App

Aplicativo Android do Edukio, escrito em Kotlin e baseado em WebView. Este guia permite configurar **do zero um computador Windows** para desenvolver, compilar, instalar, diagnosticar e gerar arquivos assinados no **Visual Studio Code**, sem precisar usar o Android Studio como editor.

## 1. O que instalar

| Componente | Versão para este projeto | Download e documentação oficial |
| --- | --- | --- |
| **VS Code** | Estável | [Baixar Visual Studio Code](https://code.visualstudio.com/download) |
| **Git** | Atual | [Git for Windows](https://git-scm.com/downloads/win) |
| **Java JDK** | **JDK 17** (recomendado: Temurin 17) | [Eclipse Temurin](https://adoptium.net/temurin/releases/?version=17) |
| **Android SDK** | **API 35** | [Android SDK Command-line Tools (na seção "Command line tools only")](https://developer.android.com/studio#command-line-tools-only) |
| **Android SDK Build-Tools** | **35.0.0** | Instalado com o gerenciador do SDK |
| **Android SDK Platform-Tools** | Atual (inclui ADB) | [Platform-Tools / ADB](https://developer.android.com/tools/releases/platform-tools) |
| **Android Studio** | Opcional | [Baixar Android Studio](https://developer.android.com/studio) |

**Já configurado no repositório (não instalar separadamente):** Gradle Wrapper **8.10.2**, Android Gradle Plugin **8.8.0** e Kotlin Plugin **1.9.22**. O comando `gradlew.bat` usa automaticamente a distribuição do Gradle definida em `gradle/wrapper/gradle-wrapper.properties`.

A compatibilidade do Android Gradle Plugin está descrita na [documentação do AGP 8.8](https://developer.android.com/build/releases/agp-8-8-0-release-notes). **Evite trocar para outra versão de Java ou Gradle sem verificar a compatibilidade.**

## 2. Preparar o Windows

### 2.1. Instalar o Git e o VS Code

1. Instale **Git for Windows** pelo link acima, mantendo a integração com terminal/`PATH`.
2. Instale o **VS Code** e habilite a opção de adicionar o comando `code` ao `PATH`, se disponível.
3. Feche e abra novamente o terminal e o VS Code.
4. Confira no **PowerShell**:

```powershell
git --version
code --version
```

Se `code` não for encontrado, ainda é possível abrir o VS Code pelo menu Iniciar e usar **File > Open Folder**.

### 2.2. Instalar o JDK 17

1. Baixe o **Eclipse Temurin JDK 17**, opção **Windows x64 / JDK / instalador MSI**, se o computador for x64.
2. Instale o JDK e identifique a pasta raiz (aquela que contém `bin`). Exemplo ilustrativo:

```text
C:\Program Files\Eclipse Adoptium\jdk-17.0.x-hotspot
```

3. Abra o menu Iniciar, pesquise **Editar as variáveis de ambiente do sistema** e escolha **Variáveis de Ambiente**.
4. Crie `JAVA_HOME` com o caminho **real da pasta raiz do JDK**, **sem** `\bin` no final.
5. Na variável `Path`, adicione `%JAVA_HOME%\bin`. Evite que um Java antigo apareça antes desse caminho.
6. Reinicie o VS Code e os terminais.

Confira:

```powershell
echo $env:JAVA_HOME
java -version
javac -version
where.exe java
```

`java` e `javac` devem indicar **17**. Se estiverem apontando para outra versão, ajuste a ordem das entradas do `Path` e abra um terminal novo.

### 2.3. Instalar o Android SDK — opção A: sem Android Studio

1. Baixe as **Android SDK Command-line Tools** (Windows) no link da tabela.
2. Crie a pasta do SDK; por exemplo, `C:\Android\Sdk`.
3. Descompacte e **organize corretamente** os arquivos na estrutura abaixo (a pasta `latest` precisa conter `bin` e `lib` diretamente):

```text
C:\Android\Sdk\
└── cmdline-tools\
    └── latest\
        ├── bin\
        │   └── sdkmanager.bat
        ├── lib\
        ├── NOTICE.txt
        └── source.properties
```

Se o ZIP criar `cmdline-tools\cmdline-tools\bin`, mova o conteúdo da pasta interna para `cmdline-tools\latest`. Não deixe um nível extra entre `latest` e `bin`.

4. Nas **Variáveis de Ambiente** do Windows, crie:

```text
ANDROID_HOME=C:\Android\Sdk
```

5. Na variável `Path`, acrescente:

```text
%ANDROID_HOME%\cmdline-tools\latest\bin
%ANDROID_HOME%\platform-tools
```

6. Reabra o PowerShell e instale os pacotes necessários:

```powershell
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="$env:ANDROID_HOME" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="$env:ANDROID_HOME" --licenses
```

Aceite as licenças solicitadas. A primeira instalação exige conexão com a internet.

Referência: [Gerenciamento de pacotes do Android SDK](https://developer.android.com/tools/sdkmanager).

### 2.4. Instalar o Android SDK — opção B: usando somente o SDK Manager do Android Studio

Se já tem Android Studio instalado, pode aproveitar o SDK existente. Abra **More Actions > SDK Manager** (ou **Settings > Languages & Frameworks > Android SDK**) e instale:

- **Android SDK Platform 35**;
- **Android SDK Build-Tools 35.0.0**;
- **Android SDK Platform-Tools**;
- **Android SDK Command-line Tools**.

Veja a localização real do SDK no SDK Manager. No Windows, normalmente será semelhante a:

```text
C:\Users\SEU_USUARIO\AppData\Local\Android\Sdk
```

Defina `ANDROID_HOME` para **essa localização real**, e acrescente ao `Path` as mesmas duas pastas mostradas na opção A. **Não é necessário abrir o Android Studio para desenvolver ou compilar depois disso.**

### 2.5. Verificar variáveis e dependências

Abra um **novo PowerShell**:

```powershell
echo $env:ANDROID_HOME
echo $env:JAVA_HOME
java -version
javac -version
adb version
git --version
```

`ANDROID_HOME` deve apontar para a raiz do SDK; `JAVA_HOME` deve apontar para o JDK 17; e os outros comandos não devem apresentar `command not found`.

**Alternativa se o Gradle não localizar o SDK:** crie, **apenas na sua máquina**, o arquivo `local.properties` na raiz do projeto com o caminho real do SDK:

```properties
sdk.dir=C:/Android/Sdk
```

Use barras `/` no caminho e ajuste se instalou o SDK em outro local. O arquivo já é ignorado pelo Git.

## 3. Clonar o projeto no VS Code

O repositório é privado: use uma conta GitHub com acesso autorizado e autentique-se quando o Git solicitar.

```powershell
git clone https://github.com/edukio/edukio_android_app.git
cd edukio_android_app
code .
```

Se `code .` não funcionar, use **VS Code > File > Open Folder** e selecione a pasta clonada. Para atualizações posteriores do repositório, use `git pull`, tomando cuidado com alterações locais ainda não enviadas.

### 3.1. Instalar extensões recomendadas

Ao abrir o projeto, o VS Code poderá sugerir as extensões de `.vscode/extensions.json`. Você também pode instalá-las pelos links:

- [Gradle for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-gradle) — visualização e execução de tarefas Gradle;
- [Kotlin](https://marketplace.visualstudio.com/items?itemName=fwcd.kotlin) — suporte à edição de Kotlin;
- [XML](https://marketplace.visualstudio.com/items?itemName=redhat.vscode-xml) — edição dos layouts e arquivos XML.

**Opcional:** [Extension Pack for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-pack), útil para ferramentas de desenvolvimento Java no VS Code.

### 3.2. Testar o Gradle e a primeira compilação

Na raiz do projeto, execute uma vez pelo terminal integrado (PowerShell):

```powershell
.\gradlew.bat --version
.\gradlew.bat assembleDebug
```

Na primeira execução, o Wrapper poderá baixar o Gradle e o projeto poderá baixar dependências do Google Maven/Maven Central; isso pode levar alguns minutos.

Se tudo estiver correto, será criado:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Depois disso, você pode fazer as tarefas comuns pelos menus do VS Code, **sem precisar digitar comandos**.

## 4. Compilar e instalar sem digitar comandos

No VS Code, vá a **Terminal > Run Task...** (ou `Ctrl+Shift+P` > **Tasks: Run Task**) e escolha:

| Tarefa configurada | Resultado |
| --- | --- |
| `Gradle: Compilar APK Debug` | Compila o APK para desenvolvimento |
| `Gradle: Instalar Debug` | Compila e instala no dispositivo |
| `Android: Compilar, instalar e abrir` | Instala e abre o Edukio |
| `Gradle: Limpar projeto` | Remove os artefatos de build |
| `Gradle: Gerar APK Release assinado` | Gera o APK release com sua chave |
| `Gradle: Gerar AAB Release assinado` | Gera o AAB release com sua chave |
| `ADB: Listar dispositivos` | Exibe dispositivos conectados |
| `ADB: Limpar Logcat` | Limpa os logs do aparelho |
| `ADB: Ver Logcat` | Exibe logs em tempo real |

**Atalho:** `Ctrl+Shift+B` executa a tarefa de compilação Debug padrão.

As tarefas Gradle utilizam o `gradlew.bat` existente no repositório. As tarefas ADB dependem de um aparelho/emulador detectado e de `adb` disponível no `Path`.

## 5. Preparar e conectar o celular Android

1. No aparelho, vá a **Configurações > Sobre o telefone** e toque repetidamente em **Número da compilação** até liberar as **Opções do desenvolvedor**. Em alguns Samsung, fica em **Sobre o telefone > Informações do software > Número de compilação**.
2. Abra **Opções do desenvolvedor** e ative **Depuração USB**.
3. Conecte o celular ao Windows com **cabo USB de dados**, desbloqueie a tela e aceite o aviso **Permitir depuração USB?**. Em seu computador de confiança, pode marcar **Sempre permitir deste computador**.
4. No VS Code, abra **Terminal > Run Task... > ADB: Listar dispositivos**. Ou verifique no PowerShell:

```powershell
adb devices -l
```

Exemplo de resultado correto:

```text
List of devices attached
R58XXXXXXXX    device
```

Se aparecer `unauthorized`, autorize na tela do celular. Se não aparecer nada, teste outra porta/cabo e confirme o driver USB do fabricante. Consulte também [Executar apps em um dispositivo físico](https://developer.android.com/studio/run/device).

Com o aparelho em `device`, selecione **Terminal > Run Task... > Android: Compilar, instalar e abrir**.

**Observação:** a versão Debug normalmente usa uma assinatura diferente da versão Release. Se o aparelho já tiver uma versão instalada com outra assinatura, a instalação poderá falhar por incompatibilidade de certificado. Não desinstale o app sem antes considerar a perda dos dados locais.

## 6. Logs (Logcat)

No VS Code:

- **Terminal > Run Task... > ADB: Ver Logcat** — acompanha os logs em tempo real; pare com o botão de encerrar a tarefa, quando terminar.
- **Terminal > Run Task... > ADB: Limpar Logcat** — limpa os registros antigos antes de reproduzir um problema.

Também é possível inspecionar pelo PowerShell:

```powershell
adb logcat
```

O Logcat contém mensagens do sistema e de outros aplicativos; nem toda linha é necessariamente um erro do Edukio.

## 7. Problemas comuns na configuração

| Problema | O que verificar |
| --- | --- |
| `java` ou `javac` não encontrado / Java diferente de 17 | Instalação do JDK 17, `JAVA_HOME` e ordem de `%JAVA_HOME%\bin` no `Path` |
| `SDK location not found` | `ANDROID_HOME` correto ou `local.properties` com `sdk.dir` |
| `adb is not recognized` | `%ANDROID_HOME%\platform-tools` no `Path`; reinicie o VS Code |
| `adb devices` vazio | Cabo de dados, porta USB, Depuração USB e driver do fabricante |
| `unauthorized` | Desbloqueie o telefone, aceite a permissão ou revogue autorizações USB e conecte novamente |
| Licenças do SDK não aceitas | Execute `sdkmanager --licenses` usando o SDK correto |
| `Failed to find Build Tools revision 35.0.0` | Instale `build-tools;35.0.0` no SDK Manager |
| Erro de download de plugins/dependências | Confira conexão, proxy/firewall e acesso a Google Maven/Maven Central |
| `gradlew.bat` falha na primeira execução | Verifique JDK 17, conexão e o Gradle Wrapper 8.10.2 |
| Falha ao instalar versão Debug sobre Release | Pode haver assinatura diferente; preserve os dados antes de remover uma instalação |

Para reiniciar o serviço do ADB, se necessário:

```powershell
adb kill-server
adb start-server
adb devices
```

---

## Assinatura de APK/AAB Release no VS Code

Se você usava **Generate Signed Bundle / APK** no Android Studio, o projeto agora permite fazer o mesmo pelo VS Code.

A chave de assinatura usada para publicar atualizações na Google Play deve ser a **mesma chave/keystore que já era usada anteriormente**. Não crie uma nova chave para substituir a existente sem saber exatamente o impacto, pois uma chave diferente pode impedir a publicação de uma atualização do mesmo aplicativo.

### 1. Localizar o keystore existente

Você precisa do arquivo usado anteriormente no Android Studio. Normalmente ele possui extensão:

```text
.jks
```

ou:

```text
.keystore
```

Também será necessário saber o **alias da chave** que você selecionava na tela de geração do aplicativo assinado.

### 2. Criar o arquivo local de configuração

Na raiz do projeto existe:

```text
keystore.properties.example
```

Faça uma cópia com o nome:

```text
keystore.properties
```

Edite apenas:

```properties
storeFile=C:/caminho/para/seu/arquivo/edukio-release.jks
keyAlias=seu_alias
```

No Windows, é recomendado usar barras `/` no caminho.

O arquivo `keystore.properties` é ignorado pelo Git e **não deve ser enviado para o GitHub**.

Os arquivos `.jks` e `.keystore` também estão no `.gitignore`.

### 3. As senhas não ficam salvas no projeto

As senhas não são armazenadas em `keystore.properties`, no Gradle ou no GitHub.

Ao executar uma tarefa de release, o VS Code solicitará:

1. **Senha do keystore**;
2. **Senha da chave/alias**.

Os campos são ocultos. Se você sempre usou a mesma senha para ambos, digite a mesma senha nas duas perguntas.

### 4. Gerar um AAB assinado para a Google Play

No VS Code:

```text
Terminal > Run Task... > Gradle: Gerar AAB Release assinado
```

O arquivo será gerado normalmente em:

```text
app/build/outputs/bundle/release/app-release.aab
```

### 5. Gerar um APK Release assinado

No VS Code:

```text
Terminal > Run Task... > Gradle: Gerar APK Release assinado
```

O APK será gerado normalmente em:

```text
app/build/outputs/apk/release/app-release.apk
```

### Segurança

Nunca envie para o GitHub:

- o arquivo `.jks` ou `.keystore`;
- `keystore.properties`;
- senha do keystore;
- senha da chave.

Faça backup seguro do keystore existente. Para aplicativos já publicados, perder a chave de assinatura pode causar problemas para publicar futuras atualizações.

## Observações finais

O Android Studio é **opcional** como ambiente de edição: pode ser usado somente para gerenciar SDK e emuladores. O desenvolvimento diário, tarefas Gradle e instalação via ADB podem ser feitos no VS Code.

**Segurança:** nunca publique `keystore.properties`, sua chave `.jks`/`.keystore` ou as senhas de assinatura. Confira o guia de assinatura acima antes de distribuir uma versão Release.
