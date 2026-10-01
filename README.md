# Edukio Android App

Aplicativo Android do Edukio baseado em WebView.

## Desenvolvimento no VS Code

O projeto contém configurações em `.vscode/` para facilitar as tarefas mais comuns sem precisar digitar os comandos manualmente.

No VS Code, abra:

```text
Terminal > Run Task...
```

As principais tarefas disponíveis são:

- `Gradle: Compilar APK Debug`
- `Gradle: Instalar Debug`
- `Android: Compilar, instalar e abrir`
- `Gradle: Limpar projeto`
- `Gradle: Gerar APK Release`
- `Gradle: Gerar AAB Release`
- `ADB: Listar dispositivos`
- `ADB: Limpar Logcat`
- `ADB: Ver Logcat`

A tarefa mais útil durante o desenvolvimento normalmente é:

```text
Android: Compilar, instalar e abrir
```

## Pré-requisitos para instalar e executar no celular

Para que as tarefas de instalação, abertura do aplicativo e Logcat funcionem, a máquina precisa ter:

- JDK compatível com o projeto;
- Android SDK;
- Android SDK Platform-Tools, que fornece o comando `adb`;
- celular Android com as Opções do desenvolvedor e a Depuração USB ativadas.

### 1. Instalar o Android SDK e o ADB

A forma mais simples é instalar o Android Studio e usar o SDK Manager apenas para instalar/manter o SDK, mesmo que o desenvolvimento seja feito no VS Code.

No Android Studio, abra:

```text
More Actions > SDK Manager
```

ou, com um projeto aberto:

```text
Settings > Languages & Frameworks > Android SDK
```

Confirme a instalação de:

```text
Android SDK Platform
Android SDK Build-Tools
Android SDK Platform-Tools
Android SDK Command-line Tools
```

O item mais importante para as tarefas do VS Code é:

```text
Android SDK Platform-Tools
```

Ele contém o executável `adb`.

### 2. Colocar o ADB no PATH

No Windows, o Platform-Tools normalmente fica em:

```text
C:\Users\SEU_USUARIO\AppData\Local\Android\Sdk\platform-tools
```

Adicione essa pasta à variável de ambiente `Path`.

Passo a passo no Windows:

1. Pesquise por **Variáveis de ambiente** no menu Iniciar.
2. Abra **Editar as variáveis de ambiente do sistema**.
3. Clique em **Variáveis de Ambiente**.
4. Em `Path`, clique em **Editar**.
5. Adicione:

```text
C:\Users\SEU_USUARIO\AppData\Local\Android\Sdk\platform-tools
```

6. Feche e abra novamente o VS Code.

Depois, teste em um terminal:

```bash
adb version
```

Se o comando mostrar a versão do Android Debug Bridge, o ADB está configurado corretamente.

No macOS ou Linux, adicione a pasta `platform-tools` do Android SDK ao `PATH` do seu shell.

### 3. Ativar as Opções do desenvolvedor no celular

No Android:

1. Abra **Configurações**.
2. Entre em **Sobre o telefone**.
3. Localize **Número da versão** ou **Número da compilação**.
4. Toque várias vezes nesse item até aparecer a mensagem de que as Opções do desenvolvedor foram ativadas.

Em aparelhos Samsung, normalmente o caminho é:

```text
Configurações > Sobre o telefone > Informações do software > Número de compilação
```

### 4. Ativar a Depuração USB

Depois de ativar as Opções do desenvolvedor:

```text
Configurações > Opções do desenvolvedor > Depuração USB
```

Ative **Depuração USB**.

### 5. Conectar o celular ao computador

Conecte o aparelho com um cabo USB que suporte transferência de dados.

Na primeira conexão com ADB, o Android deverá mostrar uma mensagem semelhante a:

```text
Permitir depuração USB?
```

Autorize o computador. Se for sua máquina de desenvolvimento, você pode marcar:

```text
Sempre permitir deste computador
```

### 6. Verificar se o aparelho foi reconhecido

No VS Code, execute:

```text
Terminal > Run Task... > ADB: Listar dispositivos
```

Ou teste manualmente:

```bash
adb devices
```

O resultado esperado é semelhante a:

```text
List of devices attached
R58XXXXXXXX    device
```

Se aparecer `unauthorized`, desbloqueie o celular e aceite a autorização de Depuração USB.

Se não aparecer nenhum dispositivo:

- teste outro cabo USB;
- teste outra porta USB;
- confirme que a Depuração USB está ativada;
- no Windows, instale o driver USB do fabricante do aparelho se necessário;
- execute `adb kill-server` e depois `adb start-server`.

### 7. Compilar, instalar e abrir pelo VS Code

Com o celular aparecendo como `device`, execute:

```text
Terminal > Run Task... > Android: Compilar, instalar e abrir
```

O VS Code irá:

1. compilar a versão Debug;
2. instalar/atualizar o APK no aparelho;
3. abrir `com.edukio.app/.MainActivity`.

## Logcat

Para acompanhar os logs do Android pelo VS Code:

```text
Terminal > Run Task... > ADB: Ver Logcat
```

Para apagar os logs antigos antes de um novo teste:

```text
Terminal > Run Task... > ADB: Limpar Logcat
```

Depois abra novamente o Logcat e reproduza o problema no aplicativo.

## Observação

O Android Studio não é obrigatório como editor deste projeto. Ele pode ser mantido apenas para facilitar a instalação e atualização do Android SDK, Build Tools, Platform-Tools e emuladores. O desenvolvimento diário pode ser feito normalmente no VS Code.
