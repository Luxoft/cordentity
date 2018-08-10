# Indy + Corda Luxoft template

The basis project combining [Hyperledger Indy Ledger](https://www.hyperledger.org/projects/hyperledger-indy) for decentralised identity with [Corda Platform](https://www.corda.net/index.html)



## Subprojects

- [indy-cordapp](cordapp/README.md) - the cordapp
- [indy-cordapp-contracts-states](cordapp-contracts-states/README.md) - contracts for the cordapp
- [indy-utils](indy-utils/README.md) - utilities for working with the Indy SDK

## External dependancies

Version cordapp 0.4.6 requires installation of indy-sdk version 1.5.0.

## Build

To run the tests you need to install the `libindy` package:

    sudo apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 68DB5E88
    sudo add-apt-repository "deb https://repo.sovrin.org/sdk/deb xenial stable"
    sudo apt-get update
    sudo apt-get install -y libindy=1.5.0

And start the `indy-pool` container on ports 9701-9708: 

    docker pull teamblockchain/indy-pool:1.5.0
    docker create -p 9701-9708:9701-9708 --name indypool teamblockchain/indy-pool:1.5.0
    docker start indypool
    
After that use the standard Gradle build procedure:

    gradle clean cleanDefaultPool build
    
### Troubleshooting    
    
Before every test run it is recommended to clean local pool and wallets data, which by default are stored in `~/.indy_client/`:

    gradle cleanDefaultPool
    
Also re-creating the `indypool` docker container is needed to get a clean system.