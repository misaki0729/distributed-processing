FROM centos:centos7
RUN yum -y update && yum clean all
RUN yum -y install wget glibc gtk2 gstreamer gstreamer-plugins-base libv4l
RUN wget -c -O /tmp/jdk-8u31-linux-x64.rpm --no-check-certificate --no-cookies --header "Cookie: oraclelicense=accept-securebackup-cookie" http://download.oracle.com/otn-pub/java/jdk/8u31-b13/jdk-8u31-linux-x64.rpm
RUN yum -y localinstall /tmp/jdk-8u31-linux-x64.rpm
RUN rm -f /tmp/jdk-8u31-linux-x64.rpm

ADD faceduker.jar /opt/faceduker/
ADD classes/haarcascade_frontalface_default.xml /opt/faceduker/
EXPOSE 8080
WORKDIR /opt/faceduker/
CMD ["java", "-Xms512m", "-Xmx1g", "-jar", "faceduker.jar", "--classifierFile=haarcascade_frontalface_default.xml"]
