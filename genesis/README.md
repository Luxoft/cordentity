# Genesis Files 

Genesis files for different networks 

Genesis File                            | Network           | Source URL
---                                     | ---               | ---
docker_pool_transactions_genesis.txt    | indy-pool         | https://github.com/hyperledger/indy-sdk/blob/master/cli/docker_pool_transactions_genesis
sovrin_test_network_genesis.txt         | Sovrin Test Net   | https://github.com/sovrin-foundation/sovrin/blob/master/sovrin/pool_transactions_sandbox_genesis

## Connect to an Indy Network

https://github.com/hyperledger/indy-sdk/blob/master/doc/getting-started/getting-started.md#step-2-connecting-to-the-indy-nodes-pool

To write and read the ledger's transactions after gaining the proper role, you'll need to make a connection to the Indy nodes pool. To make a connection to the different pools that exist, like the Sovrin pool or the local pool we started by ourselves as part of this tutorial, you'll need to set up a pool configuration.

The list of nodes in the pool is stored in the ledger as NODE transactions. Libindy allows you to restore the actual list of NODE transactions by a few known transactions that we call genesis transactions. Each Pool Configuration is defined as a pair of pool configuration name and pool configuration JSON. The most important field in pool configuration json is the path to the file with the list of genesis transactions. Make sure this path is correct.

The pool.create_pool_ledger_config call allows you to create a named pool configuration. After the pool configuration is created we can connect to the nodes pool that this configuration describes by calling pool.open_pool_ledger. This call returns the pool handle that can be used to reference this opened connection in future libindy calls.

The code block below contains each of these items. Note how the comments denote that this is the code for the "Steward Agent."

    # Steward Agent
    pool_name = 'pool1'
    pool_genesis_txn_path = get_pool_genesis_txn_path(pool_name)
    pool_config = json.dumps({"genesis_txn": str(pool_genesis_txn_path)})
    await pool.create_pool_ledger_config(pool_name, pool_config)
    pool_handle = await pool.open_pool_ledger(pool_name, None)

## Genesis Transactions
As Indy is a public permissioned blockchain, each ledger may have a number of pre-defined transactions defining the initial pool and network.

- pool genesis transactions define initial trusted nodes in the pool
- domain genesis transactions define initial trusted trustees and stewards

https://github.com/hyperledger/indy-node/blob/e2e08fa874827fd4ba4a502f05751c761a0347ec/docs/transactions.md#genesis-transactions