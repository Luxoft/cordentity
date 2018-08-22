# Indy-Cordapp

Provides the basic Corda flows for working with an Indy Ledger

## Process

### In terms of Indy

[Hyperledger Indy Ledger](https://www.hyperledger.org/projects/hyperledger-indy) is a platform for providing DIDs (Decentralized IDs) and verifiable Credentials.

Every Indy-enabled node should run an instance of [IndyService](#Services) to managed all the operations with the Indy Ledger.

The private information such as Keys and Credentials are stored in Wallet. 
The wallet is unique for every Indy user and must be created for every instance of [IndyService](#Services).
By default it is in the `~/.indy_client/` folder. 
Delete it manually or with a Gradle task `cleanDefaultPool` to have a clean run.

The shared information such as Shames and Credential Definitions are stored in Indy Pool. 
We recommend running it as a Docker container, locally or remotely.
You can download a pre-build Docker image from [DockerHub](https://hub.docker.com/r/teamblockchain/indy-pool/) or use [indy-sdk](https://github.com/hyperledger/indy-sdk) to compose an image yourselves.

#### Indy Configuration
Each [IndyService](#Services) can be configured with its own `indy.properties`

- indyuser.walletName - the name of the Wallet
- indyuser.walletType - the type of the Wallet
- indyuser.role - the role of the service
- indyuser.did - a preconfigured DID of the node
- indyuser.seed - a seed to generate private keys of the node
- indyuser.wallet - deprecated

Example `indy.properties` file:

    indyuser.walletName=Issuer
    indyuser.walletType=CordaWalletType
    indyuser.role=trustee
    indyuser.did=V4SGRU86Z58d6TV7PBUe6f
    indyuser.seed=000000000000000000000000Trustee1

#### Terminology

- DID - a.k.a. Decentralized Identity - a mean for trustable interactions with the subject (e.i. Bob)
- Attribute - a small piece of information (e.i. Bob's age)
- Schema - a digital description of Attributes an entity can provide (e.i. Bob can provide his name and age) 
- Credential - a statement about an Attribute that you can prove to 3rd party (e.i. Bob's age is more than 18)
- Credential Definition - a signed template for a Credential (e.i. Bob is ready to issue Credentials about his age)
- Wallet - local storage for private keys and saved Credentials


### In terms of Corda

[Corda Platform](https://www.corda.net/index.html) provides a secure peer-to-peer network for transporting Indy entities such as Claims, Schemas, Credentials and verifying Attributes.

#### Terminology

- Node - a peer that hosts a Corda service or executes a CorDapps application.
- Flow - a sequence of steps involving multiple Nodes and updating the Corda ledger
- State - data that can be transferred through the Corda network
- Service - a.k.a. Node Service - a sub function of a Corda Node. Usually accessible through `ServiceHub`

## Components

### Flows

- [AssignPermissionsFlow](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/flow/AssignPermissionsFlow.kt) - changes permissions of another Corda party

- [CreateClaimDefFlow](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/flow/CreateClaimDefFlow.kt) - creates a credential definition for schema and registers it

- [CreateSchemaFlow](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/flow/CreateSchemaFlow.kt) - creates an Indy scheme and registers it

- [GetDidFlow](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/flow/GetDidFlow.kt) -  requests DID of another Corda party

- [IssueClaimFlow](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/flow/IssueClaimFlow.kt) - issues an Indy credential based on a proposal

- [VerifyClaimFlow](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/flow/VerifyClaimFlow.kt) - verifies a set of predicates

### Utility Flows

- [ArtifactsRegistryFlow](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/flow/ArtifactsRegistryFlow.kt) - registers a new [IndyArtifactsRegistry](#services) service

- [CreatePairwiseFlow](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/flow/CreatePairwiseFlow.kt) - initiates a bi-directional connection

### Services

- [IndyService](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/service/IndyService.kt) - 
A Corda service for dealing with Indy Ledger infrastructure such as pools, credentials, wallets.

- [IndyArtifactsRegistry](src/main/kotlin/com.luxoft.blockchainlab.corda.hyperledger.indy/service/IndyArtifactsRegistry.kt) - 
A global Schema and Credential Definition discovery facility, a.k.a. an artifact registry. 
May be removed in the future if Hyperledger provides a similar service.
See the official documentation on [LIST_SCHEMA](https://github.com/hyperledger/indy-node/blob/c37af6befccf31afa8a31c032c88d110b819495b/design/anoncreds.md#list_schema) for details about the filters.


## Build

    gradle clean build
    
## Net Topology

The system assumes that the Corda network contains one [IndyArtifactsRegistry](#services) that keeps track of public artifacts and several [IndyService](#services) nodes that correspond to business entities. 

At least one node must be a Trustee to be able to grant permissions to other nodes. In the curent realisation a Trustee must have `indyuser.seed=000000000000000000000000Trustee1` in its configuration file. 