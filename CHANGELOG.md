# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [unreleased]

## [0.2.2] - 2016-01-01
### Changed
- Dependencies update

## [0.2.1] - 2015-12-09
### Fixed
- Upon invalid JSON either log only or close connection depending on
  the used `:json-framing` transport option.

## [0.2.0] - 2015-12-08
### Added
- JSON-RPC support: `connect-json-rpc!`

### Changed
- namespace `tcp` -> `core`
- Function `connect-rpc!` -> `connect-bson-rpc!`

## [0.1.0] - 2015-11-26
### Initial Version:
- BSON-RPC - requests and notifications over TCP connection.

[unreleased]: https://github.com/seprich/clj-bson-rpc/compare/0.2.2...HEAD
[0.2.2]: https://github.com/seprich/clj-bson-rpc/compare/0.2.1...0.2.2
[0.2.1]: https://github.com/seprich/clj-bson-rpc/compare/0.2.0...0.2.1
[0.2.0]: https://github.com/seprich/clj-bson-rpc/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/seprich/clj-bson-rpc/tree/0.1.0
