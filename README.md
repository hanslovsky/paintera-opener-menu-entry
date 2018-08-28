# paintera-opener-menu-entry

Example for creating a custom paintera opener dialog.

Checkout [paintera context-menu-open-dialog branch](https://github.com/saalfeldlab/paintera/tree/context-menu-open-dialog)
```
mvn clean install
```
Clone this repository and run
```
git checkout 0.1.0 # if on master, adjust version string accordingly below
mvn clean install
jrun org.janelia.saalfeldlab:paintera:0.3.1-SNAPSHOT:+org.slf4j:slf4j-simple --default-to-temp-directory
# press ctrl-O for open dataset context menu
jrun org.janelia.saalfeldlab:paintera:0.3.1-SNAPSHOT:+org.slf4j:slf4j-simple+org.janelia.saalfeldlab:paintera-opener-menu-entry:0.1.0 --default-to-temp-directory
# press ctrl-O for open dataset context menu and see the difference
```
