## Dynamicfees eclair plugin

This is a plugin for [eclair](github.com/ACINQ/eclair) to adjust your channel relay fees dynamically according to 
the channel balance. The plugin conceptually defines 3 states in which the channel [balance] can be and a 'multiplier'
associated to each state, the multiplier will be used to compute the new relay fees.

- Depleted  => When 'toLocal' becomes too little the channel is considered depleted
- Balanced  => When the channel is not depleted nor saturated then it's balanced, no multiplier applied
- Saturated => When 'toLocal' becomes too large the channel is considered saturated

The goal of the plugin is to keep the user's channel balanced by incentivizing the usage of depleted channels and 
disincentivizing the usage of saturated channels, with this strategy the channels naturally tend to stay in a balanced
state. Note that if all nodes on the network apply this strategy all the users would benefit from a more balanced network.
The plugin works by intercepting the relayed payments and creates a new `channel_update` if and only if the channel is 
transitioning from a state to another (i.e going balanced -> depleted), the plugin will *ignore* sent/received payments because
they are user initiated. The plugin will apply the multiplier only to the `fee_proportional` value of the relay fee and 
use the *configured value* in `eclair.fee-proportional-millionths` as basepoint for the multiplication. This means that a 
manually updated relay fee will be overridden by the plugin once there is a relayed payments that make the channel transition 
to a new state.

### Installation
The plugin can be built locally or downloaded from the release page of this repo, it's a fat jar that must be 
passed as argument to eclair when it's launched, see the [instructions](https://github.com/ACINQ/eclair#plugins).

### Usage
Once the plugin is configured and loaded it doesn't need any further input from the user.

### Configuration
Users MUST define a configuration section specifying the chosen values for their depleted/saturated thresholds
and their relative multiplier, note that the plugin only asks for a depleted and saturated thresholds and everything 
in between will be considered balanced. It is possible to specify either a blacklist OR a whitelist of channel
`short_id`s, this allows to filter only for certain channels or filter out channels that you want to exclude from 
the dynamic fees operations:

In `eclair.conf` add:

|                                         	|     	|
|-----------------------------------------	|-----	|
| eclair.dynamicfees.depleted.threshold   	| 0.3 	|
| eclair.dynamicfees.saturated.threshold  	| 0.8 	|
| eclair.dynamicfees.depleted.multiplier  	| 3   	|
| eclair.dynamicfees.saturated.multiplier 	| 0.5  	|
| eclair.dynamicfees.whitelist              | ["0x1x2"] |
| eclair.dynamicfees.blacklist              | ["3x4x5"] |


