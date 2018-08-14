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


- [ArtifactsRegistryFlow](com.luxoft.blockchainlab.corda.hyperledger.indy.flow.ArtifactsRegistryFlow)

- [AssignPermissionsFlow](com.luxoft.blockchainlab.corda.hyperledger.indy.flow.AssignPermissionsFlow) - changes permissions of another Corda party

- [CreateClaimDefFlow](com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreateClaimDefFlow) - creates a credential definition for schema and registers it

- [CreatePairwiseFlow](com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreatePairwiseFlow)

- [CreateSchemaFlow](com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreateSchemaFlow) - creates an Indy scheme and registers it

- [GetDidFlow](com.luxoft.blockchainlab.corda.hyperledger.indy.flow.GetDidFlow) -  requests DID of another Corda party

- [IssueClaimFlow](com.luxoft.blockchainlab.corda.hyperledger.indy.flow.IssueClaimFlow)

- [VerifyClaimFlow](com.luxoft.blockchainlab.corda.hyperledger.indy.flow.VerifyClaimFlow) - verifies a set of predicates

- [VerifyClaimInContractFlow](com.luxoft.blockchainlab.corda.hyperledger.indy.flow.VerifyClaimInContractFlow)



## Build

Before every run it is recommended to clean the default pool with 

    gradle cleanDefaultPool
    

## How to Run

- IndyService
- IndyArtifactsRegistry