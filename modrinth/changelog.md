## 1.1.0+26.3

### English

**Added**
- The item tooltip lists the 3 containers holding the most of the item, with the amount, the container name and the distance. Hold Shift to list all of them.
- New right-click option, *Mark the one with most*: it puts the waypoint on the container holding the most of the item.

**Changed**
- The mod is now called Chest Tracker Plus in the game, in Mod Menu and in the server log. Config folders keep their names, so your settings carry over.

### Português

**Adicionado**
- O tooltip do item mostra os 3 containers com mais quantidade do item, com a quantidade, o nome do container e a distância. Segure Shift para ver todos.
- Nova opção no botão direito, *Marcar o que tem mais*: coloca o waypoint no container com mais quantidade do item.

**Alterado**
- O mod agora se chama Chest Tracker Plus no jogo, no Mod Menu e no log do servidor. As pastas de configuração mantêm o nome, então suas configurações continuam valendo.

## 1.0.0+26.3 — first release / primeira versão

Based on Chest Tracker (Unofficial port) 2.8.4+26.3 by ponuing (original mod by JackFred).
Baseado no Chest Tracker (Unofficial port) 2.8.4+26.3 de ponuing (mod original de JackFred).

### English

**Added**
- `/chesttracker scan new` (operators): reads every chest, barrel and shulker box saved in all dimensions, straight from the region files, without loading chunks. Uses several cores (2 by default) and skips unopened loot chests.
- `/chesttracker scan` (everyone): loads the last full scan from `<world>/chesttrackermelhor/containers.dat`, without touching the world. Memories newer than the file are kept.
- After a full scan, every online player with the mod is updated, and containers that no longer exist are removed from memory.
- Scheduled full scans, every day at 03:00 Brasília time by default. They also run on an empty, paused server.
- Server settings in `config/chesttrackermelhor/servidor.properties`: `nucleos`, `horarios_scan` and `fuso_horario`, applied without a restart.
- Containers panel next to the item grid: containers with the same name, ignoring case, form one group, with a count. The selected group is strongly highlighted.
- Item tooltip shows the distance to the nearest listed container holding the item.
- Waypoints: right-click an item to add one. It draws a beacon-style beam, visible through terrain and beyond render distance, and makes the container glow. It clears when the container is opened, by right-clicking again, or with the *Clear waypoints* button. Waypoints are saved per memory bank.
- Left-clicking an item lights up every listed container holding it, for Where Is It's search duration.
- Memory lifetime option "10 days".
- Brazilian Portuguese translations for the new texts.

**Changed**
- Default memory lifetime is now 10 days, instead of 12 hours.
- A scan raises a memory bank's lifetime to at least 10 days and sets its item list range to infinite.
- Clicking a container group ignores the item list range, so every container with that name is listed.
- One jar for client and server. A dedicated server loads only the scan part and doesn't need YACL.

### Português

**Adicionado**
- `/chesttracker scan new` (operadores): lê todos os baús, barris e shulker boxes salvos em todas as dimensões, direto dos arquivos de região, sem carregar chunks. Usa vários núcleos (2 por padrão) e ignora baús de loot nunca abertos.
- `/chesttracker scan` (todos): carrega a última varredura completa de `<mundo>/chesttrackermelhor/containers.dat`, sem mexer no mundo. Memórias mais novas que o arquivo são mantidas.
- Depois de uma varredura completa, todos os jogadores online com o mod são atualizados, e os containers que não existem mais saem da memória.
- Varredura completa agendada, todo dia às 03:00 de Brasília por padrão. Funciona também com o servidor vazio e pausado.
- Configuração do servidor em `config/chesttrackermelhor/servidor.properties`: `nucleos`, `horarios_scan` e `fuso_horario`, aplicados sem reiniciar.
- Painel Containers ao lado da grade de itens: containers com o mesmo nome, sem diferenciar maiúsculas, formam um grupo, com contagem. O grupo selecionado fica bem destacado.
- O tooltip do item mostra a distância até o container listado mais próximo que tem o item.
- Waypoints: clique com o botão direito em um item para marcar. Aparece um feixe de luz, visível através do terreno e além da distância de renderização, e o container brilha. O waypoint sai ao abrir o container, ao clicar com o botão direito de novo ou pelo botão *Limpar waypoints*. Os waypoints ficam salvos por banco de memória.
- Clicar com o botão esquerdo em um item acende todos os containers listados que têm o item, enquanto durar a busca do Where Is It.
- Opção de duração das memórias "10 dias".
- Traduções em português do Brasil para os textos novos.

**Alterado**
- A duração padrão das memórias agora é 10 dias, em vez de 12 horas.
- A varredura sobe a duração do banco de memória para pelo menos 10 dias e deixa infinito o alcance da lista de itens.
- Clicar em um grupo de containers ignora o alcance da lista de itens, então todos os containers com aquele nome aparecem.
- Um único jar para cliente e servidor. O servidor dedicado carrega só a parte da varredura e não precisa do YACL.
