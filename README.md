# NetworkBridge

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![GitHub Issues](https://img.shields.io/github/issues/rahim401/networkbridge.svg)](https://github.com/rahim401/networkbridge/issues)
[![GitHub Stars](https://img.shields.io/github/stars/rahim401/networkbridge.svg)](https://github.com/rahim401/networkbridge/stargazers)

This library consists of abstract classes that add additional functionalities to your connection, such as:
- **HeartBeat**: Ensures the connection between two bridges is operational
- **Data Handling**: Provides a systematic way to process data from streams.
- **Request-Response**: An effective way to send and receive requests and responses.
- **Multi-Stream Support**: A method to create, manage, and reuse multiple streams of different kinds.

It offers all these functionalities in a generic, scalable, thread-safe, and understandable manner. Thus, it can be easily implemented with any connection that uses streams, such as Tcp, Ssl, Bluetooth, and more.

### Project Structure:
- **bridgeCore**: Package containing all the core classes.
  - Bridge: The base class handling Heartbeat, ReadingData, and Disconnection.
  - RequestBridge: Subclass of Bridge for handling Requests and Responses.
  - StreamBridge: Subclass of Bridge for handling multiple streams.
- **examples**: Set of Tcp-based examples for all bridges.
  - basicBridge: A basic Master-Slave implementation of Bridge.
  - chatBridge: A CLI-based two-way chatting system built on top of RequestBridge.
  - mathBridge: A Server-Client-like, CLI-based program to evaluate Math Expressions.
  - streamBridge: A CLI-based program to create, read, or write multiple streams.

This project is written in Kotlin v1.6 and compiled for Java v11. However, you can easily customize or migrate it to any language based on your needs. 


## Contributing

If you would like to contribute to this project, please follow these guidelines:
1. Fork this repository to your own account.
2. Create a new branch for your feature or bug fix.
3. Write your code and commit your changes.
4. Push your changes to your forked repository.
5. Submit a pull request to this repository.

## License

The NetworkBridge is licensed under the [MIT License](LICENSE).