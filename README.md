# Micro java samples.

* [algs](algs/README.md) - Algorithms
* [BDD](bdd/README.md) - Behaviour Driven Development
* [echo](echo/README.md) - Different Server types
* [elastic](elastic/README.md) - rest to elasticsearch
* [linker](linker/README.md) - Simple short to full url transformer
* [mathparser](mathparser/README.md) - Complex Math parser with GUI
* [modules](modules/README.md) - Pure JPMS Example
* [openflights](openflights/README.md) - OpenFlights
* [SES](ses/README.md) - Simple Event System
* [soap2rest](soap2rest/README.md) - soap to rest
* [tic-tac-toe](tic-tac-toe/README.md) - Tic-tac-toe game with GUI
* [vfs](vfs/README.md) - Virtual File Server
* [xlspaceship](xlspaceship/README.md) - Battleship game
* [xsd2model](xsd2model/README.md) - xsd2model

## Dependency management

### Overview of dependencies

```bash
mvn dependency:tree
```

### To find unused dependencies
```bash
mvn dependency:analyze
```

### To check new dependencies
```bash
mvn versions:display-dependency-updates
```

