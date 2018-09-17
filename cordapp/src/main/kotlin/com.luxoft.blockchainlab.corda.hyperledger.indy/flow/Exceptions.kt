package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import net.corda.core.flows.FlowException

class IndyCredentialDefinitionAlreadyExistsException(credDefId: String)
    : FlowException("Credential definition with id: $credDefId is already exists")

class IndyCredentialMaximumReachedException(revRegId: String)
    : FlowException("Revocation registry with id: $revRegId cannot hold more credentials")

class IndySchemaAlreadyExistsException(schemaId: String)
    : FlowException("Schema with id $schemaId already exists")