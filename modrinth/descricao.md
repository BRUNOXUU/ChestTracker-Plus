# Chest Tracker Melhor

**A fork of [Chest Tracker (Unofficial port)](https://modrinth.com/mod/chest-tracker-port) for Minecraft 26.3**, which itself continues JackFred's original [Chest Tracker](https://modrinth.com/mod/chest-tracker). Everything the port does still works. This fork adds a world-wide container scan, named container groups, item distances and waypoints.

> 🇧🇷 Versão em português logo abaixo.

## ✨ What's new compared to Chest Tracker (Unofficial port)

### Scan every container in the world
- **`/chesttracker scan new`** (operators): reads every chest, barrel and shulker box saved in the Overworld, the Nether, the End and modded dimensions, straight from the region files, without loading any chunk. It runs on several cores and adds what it finds to your Chest Tracker memory.
- **`/chesttracker scan`** (everyone): loads the results of the last full scan from a file, without touching the world. Anything you saw yourself after that scan is kept.
- After a full scan, **every online player with the mod is updated**. Containers that no longer exist are removed from memory.
- **Scheduled scans**: the server can run `scan new` by itself every day. By default this happens at 03:00 (Brasília time), and it also works when nobody is online.
- Unopened loot chests are skipped, because their items don't exist until someone opens them.

### Find things faster
- **Containers panel**: chests with the same name are grouped. Rename a few chests "BLOCKS" and a **BLOCKS** group appears, linking all of them wherever they are. The selected group is clearly highlighted.
- **Distance in the tooltip**: hovering an item shows how far away the nearest container holding it is.
- **Waypoints**: right-click an item and choose *Add waypoint*. A beacon-style beam rises from the nearest container holding it, and the container glows through walls. The beam is visible underground and beyond your render distance. It clears when you open the container, when you right-click the item again, or with the *Clear waypoints* button. Waypoints are saved between sessions.
- **Left click highlight**: clicking an item lights up every listed container holding it, for as long as the Where Is It search lasts.

### Memory
- New memory lifetime option: **10 days**, which is now the default. A scan raises any shorter lifetime to 10 days and makes the item list range infinite, so far chests show up.

### One jar for everything
- The same `.jar` works on the client and on the server. A dedicated server loads only the scan part, so it doesn't need YACL.

## ⚙️ Server settings
`config/chesttrackermelhor/servidor.properties` (changes apply without a restart):
```properties
nucleos=2                       # CPU cores for scan new
horarios_scan=03:00             # daily scans, comma separated; empty disables
fuso_horario=America/Sao_Paulo  # time zone of the times above
```
The saved scan lives in `<world>/chesttrackermelhor/containers.dat`.

## 📥 Requirements
- **Client:** [Fabric API](https://modrinth.com/mod/fabric-api) and [YACL](https://modrinth.com/mod/yacl).
- **Server (optional, for the scan commands):** the same jar and Fabric API.
- **Remove the original Chest Tracker**: both use the same mod id, so the game won't start with both. Your existing memories keep working.

## ⚠️ Good to know
- The scan reads chests, barrels and shulker boxes. Ender chests are per player and are not scanned.
- Minecraft doesn't record who placed a block, so the scan can't tell player chests from generated ones. Empty unnamed containers are discarded, as Chest Tracker always does.
- Client and server need the same version for the scan commands. Everything else works on any server.

## 📜 Credits and license
- Original mod: **JackFred**. Unofficial port: **ponuing**. Changes in this fork: **BRUNU**.
- Embeds [Where Is It](https://modrinth.com/mod/where-is-it-port), JackFredLib and [Searchables](https://modrinth.com/mod/searchables).
- License **LGPL-3.0-only**. The source code of this version is attached to every release as `-sources.zip`.
- Made with the help of AI (Claude).

---

# 🇧🇷 Chest Tracker Melhor (português)

**Um fork do [Chest Tracker (Unofficial port)](https://modrinth.com/mod/chest-tracker-port) para Minecraft 26.3**, que por sua vez continua o [Chest Tracker](https://modrinth.com/mod/chest-tracker) original do JackFred. Tudo que o port faz continua funcionando, e este fork acrescenta varredura de containers do mundo inteiro, grupos de containers por nome, distância dos itens e waypoints.

## ✨ O que mudou em relação ao Chest Tracker (Unofficial port)

### Varredura de todos os containers do mundo
- **`/chesttracker scan new`** (operadores): lê todos os baús, barris e shulker boxes salvos no Overworld, Nether, End e dimensões de mods, direto dos arquivos de região, sem carregar chunks. Usa vários núcleos e adiciona tudo à memória do Chest Tracker.
- **`/chesttracker scan`** (todos): carrega de um arquivo o resultado da última varredura completa, sem mexer no mundo. O que você mesmo viu depois dessa varredura é mantido.
- Depois de uma varredura completa, **todos os jogadores online com o mod são atualizados**, e os containers que não existem mais são removidos da memória.
- **Varredura agendada**: o servidor pode rodar o `scan new` sozinho todo dia. Por padrão às 03:00, horário de Brasília, inclusive sem ninguém online.
- Baús de loot nunca abertos são ignorados, porque os itens ainda não existem.

### Ache as coisas mais rápido
- **Painel Containers**: baús com o mesmo nome viram um grupo. Renomeie alguns baús como "BLOCOS" e aparece o grupo **BLOCOS**, ligando todos eles, estejam onde estiverem. O grupo selecionado fica bem destacado.
- **Distância no tooltip**: ao passar o mouse em um item, aparece a distância até o container mais próximo que tem esse item.
- **Waypoints**: clique com o botão direito em um item e escolha *Marcar waypoint*. Um feixe de luz sobe do container mais próximo que tem o item, e o container brilha através das paredes. O feixe aparece debaixo da terra e além da distância de renderização. Ele sai quando você abre o container, clica com o botão direito no item de novo ou usa o botão *Limpar waypoints*. Os waypoints ficam salvos entre sessões.
- **Destaque no clique esquerdo**: clicar em um item acende todos os containers listados que têm esse item, enquanto durar a busca do Where Is It.

### Memória
- Nova opção de duração das memórias: **10 dias**, que agora é o padrão. A varredura sobe para 10 dias qualquer duração menor e deixa infinito o alcance da lista de itens, para os baús distantes aparecerem.

### Um único jar
- O mesmo `.jar` funciona no cliente e no servidor. O servidor dedicado carrega só a parte da varredura, então não precisa do YACL.

## ⚙️ Configuração do servidor
`config/chesttrackermelhor/servidor.properties` (vale sem reiniciar):
```properties
nucleos=2                       # núcleos usados pelo scan new
horarios_scan=03:00             # horários diários, separados por vírgula; vazio desativa
fuso_horario=America/Sao_Paulo  # fuso dos horários acima
```
A varredura salva fica em `<mundo>/chesttrackermelhor/containers.dat`.

## 📥 Requisitos
- **Cliente:** [Fabric API](https://modrinth.com/mod/fabric-api) e [YACL](https://modrinth.com/mod/yacl).
- **Servidor (opcional, para os comandos de varredura):** o mesmo jar e a Fabric API.
- **Remova o Chest Tracker original**: os dois usam o mesmo id, então o jogo não abre com ambos. Suas memórias atuais continuam funcionando.

## ⚠️ Bom saber
- A varredura lê baús, barris e shulker boxes. O baú do End é de cada jogador e não entra.
- O Minecraft não registra quem colocou um bloco, então a varredura não separa baús de jogadores dos gerados pelo mundo. Containers vazios e sem nome são descartados, como o Chest Tracker sempre fez.
- Cliente e servidor precisam da mesma versão para os comandos de varredura. O resto funciona em qualquer servidor.

## 📜 Créditos e licença
- Mod original: **JackFred**. Port não oficial: **ponuing**. Alterações deste fork: **BRUNU**.
- Inclui [Where Is It](https://modrinth.com/mod/where-is-it-port), JackFredLib e [Searchables](https://modrinth.com/mod/searchables).
- Licença **LGPL-3.0-only**. O código-fonte de cada versão vai anexado como `-sources.zip`.
- Feito com ajuda de IA (Claude).
