# Indy-Cordapp

Provides the basic Corda flows for working with an Indy Ledger

//todo: преимущества симбиоза двух платформ + что такое инди

## Process

### In terms of Indy
about indy claims and DIDs
everyone has a wallet + how to configure


### In terms of Corda

transport level for Indy operations
- issuing claim, 
- issuing schema, 
- issuing credentials - 2 
- verification - attributes


## Flows


- [ArtifactsRegistryFlow](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/flow/ArtifactsRegistryFlow.kt)

- [AssignPermissionsFlow](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/flow/AssignPermissionsFlow.kt) - changes permissions of another Corda party

- [CreateClaimDefFlow](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/flow/CreateClaimDefFlow.kt) - creates a credential definition for schema and registers it

- [CreatePairwiseFlow](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/flow/CreatePairwiseFlow.kt)

- [CreateSchemaFlow](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/flow/CreateSchemaFlow.kt) - creates an Indy scheme and registers it

- [GetDidFlow](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/flow/GetDidFlow.kt) -  requests DID of another Corda party

- [IssueClaimFlow](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/flow/IssueClaimFlow.kt) - issues an Indy credential based on a proposal and registers it

- [VerifyClaimFlow](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/flow/VerifyClaimFlow.kt) - verifies a set of predicates

- [VerifyClaimInContractFlow](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/flow/VerifyClaimInContractFlow.kt)


## Services

- [IndyService](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/service/IndyService.kt) - 
A Corda service for dealing with Indy Ledger infrastructure such as pools, credentials, wallets.

- [IndyArtifactsRegistry](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/service/IndyArtifactsRegistry.kt) - 
A global Schema and Credential Definition discovery facility, a.k.a. an artifact registry. 
May be removed in the future if Hyperledger provides a similar service.


## Build

Before every run it is recommended to clean the default pool with 

    gradle cleanDefaultPool