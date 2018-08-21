# Indy + Corda Luxoft template

The basis project combining [Hyperledger Indy Ledger](https://www.hyperledger.org/projects/hyperledger-indy) with [Corda Platform](https://www.corda.net/index.html).

It is often required to share parts of private data and prove assertions based on such data. 
For example, a person can prove that her age is above the legal age without disclosing how old she is.
Indy makes it possible to prove a statement is true while preserving confidentiality.
Indy-Cordapp project integrates Indy capabilities into a Corda-based environment.

## Usage

For full information about the processes and APIs see [cordapp/README](cordapp/README.md)

### Business Case "Legal Age Verification"
   
In many countries a person must be above the legal age to purchase alcohol drinks.
Using services and flows provided by Indy-Codrapp it is possible to create a system 
that proves that the age of a customer is above the legal limit without exposing their actual age.
   
Lets assume that those 4 nodes are connected as a part of a Corda network:
 - ministry - the Ministry of Home Affairs service
 - store    - a grocery store payment center
 - alice    - Alice's mobile device
 - artifactory - Global Artifactory Service
 

    lateinit var ministry: StartedNode<*>
    lateinit var alice: StartedNode<*>
    lateinit var store: StartedNode<*>
    lateinit var artifactory: StartedNode<*>
    
Each Corda node has a X500 name:
    
    val ministryX500 = ministry.info.singleIdentity().name
    val aliceX500 = alice.info.singleIdentity().name
    val artifactoryX500 = artifactory.info.singleIdentity().name

And each Indy node has a Decentralized Identity:

    val ministryDID = store.services.startFlow(
                    GetDidFlow.Initiator(ministryX500)).resultFuture.get()

To allow customers and shops to communicate, Ministry issues a shopping scheme:

    val schemaId = ministry.services.startFlow(
            CreateSchemaFlow.Authority(
                    "shopping scheme",
                    "1.0",
                    listOf("NAME", "BORN"),
                    artifactoryX500)).resultFuture.get()

Ministry creates a claim definition for the shopping scheme:

    val schemaDetails = IndyUser.SchemaDetails("shopping scheme", "1.0", ministryDID)

    ministry.services.startFlow(
            CreateClaimDefFlow.Authority(schemaDetails, artifactoryX500)).resultFuture.get()

Ministry verifies Alice's legal status and issues her a shopping credential:

    val credentialProposal = """
        {
        "NAME":{"raw":"Alice", "encoded":"119191919"},
        "BORN":{"raw":"2000",  "encoded":"2000"}
        }
        """

    ministry.services.startFlow(
            IssueClaimFlow.Issuer(
                    UUID.randomUUID().toString(),
                    schemaDetails,
                    credentialProposal,
                    aliceX500,
                    artifactoryX500)).resultFuture.get()

When Alice comes to grocery store, the store asks Alice to verify that she is legally allowed to buy drinks:

    // Alice.BORN >= currentYear - 18
    val eighteenYearsAgo = LocalDateTime.now().minusYears(18).year
    val legalAgePredicate = VerifyClaimFlow.ProofPredicate(schemaDetails, ministryDID, "BORN", eighteenYearsAgo)

    val verified = store.services.startFlow(
            VerifyClaimFlow.Verifier(
                    UUID.randomUUID().toString(),
                    emptyList(),
                    listOf(legalAgePredicate),
                    aliceX500,
                    artifactoryX500)).resultFuture.get()

If the verification succeeds, the store can be sure that Alice's age is above 18.

    println("You can buy drinks: $verified")
    

### Business Cases "Personalized Health Care Supply Chain"

//todo: Link to poc-supply -chain

Another use case for Indy CorDapp is a Personalized Health Care Supply Chain.

This system allows sharing private patients' information while providing extensive control over the usage of that information.

The connected parties in this case would usually be Insurance Providers, Patients, Hospitals, Personal Medicine Manufacturers and Government Agencies.
The sensitive information may include patient’s age, nationality, results of medical analyses or guarantee of insurance coverage.

Thanks to our Indy CorDapp solution, patient’s personal data is disclosed only to the eligible parties and only to the extent required in each particular business case.
 

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

## Contributors

- [Alexander Kopnin](https://github.com/alkopnin)
- [Alexey Koren](https://github.com/alexeykoren)
- [Daniil Vodopian](https://github.com/voddan/)
