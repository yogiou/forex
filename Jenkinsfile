// Jenkins Pipeline for Forex (Scala/sbt). For Pipeline-from-SCM: point the job at this file.
// Requires: Java 11+ and sbt on the agent, or use a Docker agent with a Scala/sbt image.

pipeline {
  agent any

  options {
    buildDiscarder(logRotator(numToKeepStr: '20'))
    timeout(time: 30, unit: 'MINUTES')
    timestamps()
  }

  stages {
    stage('Build') {
      steps {
        sh 'sbt -batch compile'
      }
    }

    stage('Test') {
      steps {
        sh 'sbt -batch test'
      }
      post {
        always {
          junit allowEmptyResults: true, testResults: 'target/test-reports/*.xml'
        }
      }
    }

    stage('Format check') {
      steps {
        sh 'sbt -batch scalafmtCheckAll'
      }
    }
  }

  post {
    failure {
      echo 'Pipeline failed. Check logs.'
    }
    success {
      echo 'Pipeline succeeded.'
    }
  }
}
