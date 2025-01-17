def generate_add_user_script() {
    stage('generate_add_user_script') {
        script {
          sh '''#!/bin/sh
              my_UID=$(id -u)
              my_GID=$(id -g)
              my_NAME=$(whoami)
              cat <<EOF > generate_add_user_script.sh
              #!/bin/sh
              if [ -f "/etc/alpine-release" ]; then
              	addgroup -g $my_GID $my_NAME
              	adduser -u $my_UID -g $my_GID -D -S $my_NAME
              else
              	groupadd -g $my_GID $my_NAME
              	useradd -u $my_UID -g $my_GID $my_NAME
              fi

              mkdir -p /home/$my_NAME >/dev/null 2>&1
              chown -R $my_NAME:$my_GID /home/$my_NAME
          '''
          sh 'chmod +x generate_add_user_script.sh'
        }//script
    }//stage
}

def harvest_log(nsre_url="https://10.100.9.223") {
    stage('harvest_log') {
        //This only can run on master. Thus we have to create a downstream job
        //to be autotrigger to save log into the master and process it.
        //Currently this func is used for Deploy plan only to deal with ansible
        //log. The generic log is done through the more generic build plan -
        //see the func apply_maintenance_policy_per_branch below.
        withCredentials([string(credentialsId: 'NSRE_JWT_API_KEY', variable: 'NSRE_JWT_API_KEY')]) {
        sh """nsre -m setup -c /tmp/nsre-\$\$.yaml -url ${nsre_url} -f ${BUILD_TAG}.log -jwtkey ${NSRE_JWT_API_KEY} -appname ${BUILD_TAG}
              nsre -m tail -c /tmp/nsre-\$\$.yaml
              rm -f /tmp/nsre-\$\$.yaml
        """
        }
    }
}

def run_log_harvest_job() {
   stage('run_log_harvest_job') {
   def MULTI_BRANCH = ""
   def _l = "${JOB_NAME}".split("/")
   if (_l.length > 1) {
       MULTI_BRANCH = "yes"
   } else {
       MULTI_BRANCH = "no"
   }

   build job: 'RUN-log-harvest', parameters: [
      string(name: 'UPSTREAM_BUILD_NUMBER', value: "${BUILD_NUMBER}"),
      string(name: 'UPSTREAM_JOB_NAME', value: "${JOB_NAME}"),
      string(name: 'MULTI_BRANCH', value: MULTI_BRANCH),
      ],
      wait: false
   }
}

def generate_aws_environment() {
    stage('generate_aws_environment') {
        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: "${PROFILE}", secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID_XVT', credentialsId: "xvt_aws", secretKeyVariable: 'AWS_SECRET_ACCESS_KEY_XVT']]) {
            withCredentials([string(credentialsId: 'GITHUB_TOKEN', variable: 'GITHUB_TOKEN')]) {
                try {
                    //Trying to parse test if ANSIBLE_VAULT_ID is defined by
                    //two means - from groovy variable scope or from env. which
                    //is generated by Jenkins while parsing the build
                    //parameters. Thus the caller can have two way to supply
                    //this var or not supply at all - then we wont generate the
                    //ansible vault related code.
                    ANSIBLE_VAULT_ID  = env.ANSIBLE_VAULT_ID ?: ANSIBLE_VAULT_ID
                    withCredentials([string(credentialsId: "${ANSIBLE_VAULT_ID}", variable: 'VAULT')]) {
                        //As we do not run code within this block, we pick up
                        //the value and push it to env value to be
                        //used in the next script shell generation
                        env.VAULT = VAULT
                    }//withCred
                }
                catch(Exception ex) {
                    println("No ANSIBLE_VAULT_ID given")
                    env.VAULT = ''
                }
            sh '''cat <<EOF > generate_aws_environment.sh
#!/bin/bash -e
mkdir -p ~/.aws

printf "[$PROFILE]
output=json
region=ap-southeast-2

[xvt_aws]
output=json
region=ap-southeast-2" > ~/.aws/config

printf "[$PROFILE]
aws_access_key_id = ${AWS_ACCESS_KEY_ID}
aws_secret_access_key = ${AWS_SECRET_ACCESS_KEY}

[xvt_aws]
aws_access_key_id = ${AWS_ACCESS_KEY_ID_XVT}
aws_secret_access_key = ${AWS_SECRET_ACCESS_KEY_XVT}

" > ~/.aws/credentials

if [ "x${ROUTE53_AWS_ACCESS_KEY_ID}" != "x" ]; then
    printf "[xvt_aws_route53]
aws_access_key_id = ${ROUTE53_AWS_ACCESS_KEY_ID}
aws_secret_access_key = ${ROUTE53_AWS_SECRET_ACCESS_KEY}

" >> ~/.aws/credentials

fi

if [ "${VAULT}" != "" ]; then
    VAULT_FILE=\\$(grep -Po '(?<=vault_password_file = )[^\\s]+' ansible.cfg | sed 's/~\\///')
    echo "Vault file path: ~/\\${VAULT_FILE}"
    mkdir -p \\$(dirname ~/\\${VAULT_FILE})
    echo "${VAULT}" > ~/\\${VAULT_FILE}
    chmod 0600 ~/\\${VAULT_FILE}
    echo "Vault file: "
    ls -lha ~/\\${VAULT_FILE}
fi

sed -i "s|git+ssh://git|https://${GITHUB_TOKEN}|g" requirements.yml
./ansible-common/update-galaxy.py

ls -lha ~/.aws/
echo "Completed run generate_aws_environment.sh"

EOF
'''
          sh 'chmod +x generate_aws_environment.sh'
        }//withCred github
      }//withCred AWS
      }//withCred AWS
    }//stage
}

def run_build_script(arg1=[:]) {
    def default_arg = ['docker_net_opt': '--net=container:xvt', 'docker_volume_opt': '--volumes-from xvt_jenkins', 'docker_image': 'xvtsolutions/python3-aws-ansible:2.9.1', 'extra_build_scripts': [], 'run_as_user': [:] ]

    def default_build_scripts = [
            'generate_add_user_script.sh',
            'generate_aws_environment.sh'
        ]

    def arg = default_arg + arg1
    def build_scripts = default_build_scripts + arg.extra_build_scripts

    def current_user = sh(returnStdout: true, script: "whoami").trim()
    def default_run_as_user = ['default_user': current_user, 'generate_add_user_script.sh': 'root']
    def run_as_user = arg.run_as_user + default_run_as_user

    // run build.sh at last
    build_scripts.add('build.sh')

    stage('run_build_script') {
        script {
            def DOCKER_WORKSPACE = null
            try {
                DOCKER_WORKSPACE = arg.docker_volume_opt.replaceAll(/-v[\s]+/,'').split(':')[1]
            }
            catch (Exception ex) {
                echo "${ex}"
                DOCKER_WORKSPACE = "${WORKSPACE}"
            }

            docker.image(arg.docker_image).withRun("-u root ${arg.docker_volume_opt} ${arg.docker_net_opt}") { c->
                build_scripts.each { script_name ->
                    if (fileExists(script_name)) {
                        def _run_as_user = run_as_user[script_name]?:run_as_user.default_user
                        sh "docker exec --user ${_run_as_user} --workdir ${DOCKER_WORKSPACE} ${c.id} bash ./${script_name}"
                        sh "rm -f ${script_name}"
                    }
                    else {
                        echo "${script_name} does not exist - skipping"
                    }
                }//each
            }//docker env
        }//script
    }//stage
}

def remove_file(file_name) {
    if (isUnix()) {
        sh "rm -f ${file_name} || true"
    }
    else {
        powershell """Remove-Item -Path '${file_name}'
        exit 0
        """
    }
}

//DONT USE THIS FOR PARAMETERISED JOB - Due to jenkins bug, see work around below from https://issues.jenkins-ci.org/browse/JENKINS-43758 but it is too ugly for me to use.
//The Multi branch build is fine as they dont have any parameters

def apply_maintenance_policy_per_branch() {

    run_log_harvest_job()

    if ((env.BRANCH != "") && ((env.ENV != "" ) || (env.APP_ENV != "")) ) {
        echo "This is parameterised job. Skipping all properties settings"
    } else {
        echo "BRANCH_NAME: ${env.BRANCH_NAME}"

        if ( env.BRANCH_NAME ==~ /release.*/ ) {
            echo "Process branch matches 'release'"
            properties([buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: ''))])
        }
        else if (env.BRANCH_NAME == "develop" || env.BRANCH_NAME == "master") {
            echo "Process branch matches 'develop'"
            properties([buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '7', daysToKeepStr: '', numToKeepStr: ''))])
        }
        else {
            echo "Process branch others than 'develop', 'release-XXX'"
            properties([buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '1', daysToKeepStr: '', numToKeepStr: ''))])
        }
    }
}

/**
 * This exists primarily because of a bug in Jenkins pipeline that causes
 * any call to the "properties" closure to overwrite all job property settings,
 * not just the ones being set.  Therefore, we set all properties that
 * the generator may have set when it generated this job (or a human).
 *
 * @param settingsOverrides a map, see defaults below.
 * @return
 */
def setJobProperties(Map settingsOverrides = [:]) {
    def settings = [discarder_builds_to_keep:'10', discarder_days_to_keep: '', cron: null, paramsList: [], upstreamTriggers: null, disableConcurrentBuilds: false] + settingsOverrides

//    echo "Setting job properties.  discarder is '${settings.discarder_builds_to_keep}' and cron is '${settings.cron}' (${settings.cron?.getClass()})"
    def jobProperties = [
            //these have to be strings:
            buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: "${settings.discarder_days_to_keep}", numToKeepStr: "${settings.discarder_builds_to_keep}"))
    ]

    if (settings.cron) {
        jobProperties << pipelineTriggers([cron(settings.cron)])
    }

    if (settings.upstreamTriggers) {
        jobProperties << pipelineTriggers([upstream(settings.upstreamTriggers)])
    }

    if (settings.disableConcurrentBuilds) {
        jobProperties << disableConcurrentBuilds()
    }

    if (settings.paramsList?.size() > 0) {
        def generatedParams = []
        settings.paramsList.each { //params are specified as name:default:description
            def parts = it.split(':', 3).toList() //I need to honor all delimiters but I want a list
            generatedParams << string(name: "${parts[0]}", defaultValue: "${parts[1] ?: ''}", description: "${parts[2] ?: ''}", trim: true)
        }
        jobProperties << parameters(generatedParams)
    }

    echo "Setting job properties: ${jobProperties}"

    properties(jobProperties)
}


def save_build_data(build_data=[:]) {
    stage('save_build_data') {
        script {
            def default_data = [
                build_number: "${BUILD_NUMBER}",
                branch_name: "${BRANCH_NAME}",
                git_revision: "${GIT_REVISION}",
                upstream_build_url: "${BUILD_URL}",
                upstream_job_name: "${JOB_NAME}",
                upstream_job_base_name: "${JOB_BASE_NAME}",
                artifact_version: "${BUILD_VERSION}"
                ]
            def data = default_data + build_data
            remove_file('artifact_data.yml')
            writeYaml file: 'artifact_data.yml', data: data
            archiveArtifacts allowEmptyArchive: true, artifacts: 'artifact_data.yml', fingerprint: true, onlyIfSuccessful: true
        } //script
    }// Gather artifacts
}

def load_upstream_build_data() {
    stage('load_upstream_build_data') {
        script {
            try {
            if (env.UPSTREAM_BUILD_NUMBER == 'LAST_SAVED_BUILD') {
              copyArtifacts filter: 'artifact_data.yml', fingerprintArtifacts: true, flatten: true, projectName: "${UPSTREAM_JOB_NAME}", selector: latestSavedBuild()
            }
            else if (env.UPSTREAM_BUILD_NUMBER == 'LAST_SUCCESS_BUILD')  {
                copyArtifacts filter: 'artifact_data.yml', fingerprintArtifacts: true, flatten: true, projectName: "${UPSTREAM_JOB_NAME}", selector: lastSuccessful()
            }
            else {
              copyArtifacts filter: 'artifact_data.yml', fingerprintArtifacts: true, flatten: true, projectName: "${UPSTREAM_JOB_NAME}", selector: specific("${UPSTREAM_BUILD_NUMBER}")
            }//If
            // Parsing artifact data
            ARTIFACT_DATA = readYaml(file: 'artifact_data.yml')
            ARTIFACT_DATA.each { k, v ->
                ARTIFACT_DATA[k] = v.replaceAll(/^"/,'').replaceAll(/"$/,'')
            }
            env.ARTIFACT_FILENAME = ARTIFACT_DATA.artifact_filename ?: (env.ARTIFACT_FILENAME ?: null)
            env.UPSTREAM_REVISION = ARTIFACT_DATA.git_revision ?: (env.UPSTREAM_REVISION ?: null)
            env.ARTIFACT_REVISION = ARTIFACT_DATA.artifact_revision ?: (ARTIFACT_DATA.git_revision ?: (env.ARTIFACT_REVISION ?: null))
            env.ARTIFACT_VERSION = ARTIFACT_DATA.artifact_version ?: (env.ARTIFACT_VERSION ?: null)
            env.UPSTREAM_BUILD_NUMBER = ARTIFACT_DATA.build_number ?: (env.UPSTREAM_BUILD_NUMBER ?: null)
            env.UPSTREAM_BRANCH_NAME = ARTIFACT_DATA.branch_name ?: (env.UPSTREAM_BRANCH_NAME ?: null)
            env.UPSTREAM_BUILD_URL = ARTIFACT_DATA.upstream_build_url ?: (env.UPSTREAM_BUILD_URL ?: null)
            env.UPSTREAM_JOB_NAME = ARTIFACT_DATA.upstream_job_name ?: (env.UPSTREAM_JOB_NAME ?: null)
            env.ARTIFACT_CLASS = ARTIFACT_DATA.artifact_class ?: (env.ARTIFACT_CLASS ?: null)

            } catch (Exception e) {
                echo "Unable to load_upstream_build_data - ${e}"
            }
        }//script
    }//stage
}


def is_sub_map(m0, m1, regex_match=[:]) {
//Test if a map m0 a sub map of m1. sub map is defined that for all keys in m0
//m1 must have that key and m1 must have the value that is equal of m0 pair.
//The value can be match using regex per key - by default is exact match.
    def filter_keys = m0.keySet()
    def output = true
    for (filter_key in filter_keys) {
        if (! regex_match[filter_key]) {
            if (! (m1.containsKey(filter_key) && m1[filter_key] == m0[filter_key]) ) {
                output = false
                break
            }
        }
        else {
            def m0_val = m0[filter_key]
            def m1_val = m1[filter_key]
            if (! (m1.containsKey(filter_key) && m1_val.matches(".*${m0_val}.*")))  {
                output = false
                break
            }
        }
    }
    return output
}

def get_build_properties(job_name, param_filter=[:], regex_match=[:]) {
//Get the param of the last success build of a job_name matching the
//param_filter map.
//The regex_match is a dict of 'field_name': true|false where filed_name is in
//the param_filter to state that we should apply regex match on it or not
//It actually like wildcard match - will be appended and prepended with .* to the string
    stage('get_build_param_by_name') {
        script {
            def output = [:]
            def selected_build = null

            Jenkins.instance.getAllItems(Job).findAll() {job -> job.name == job_name}.each{
                def selected_param_kv = [:]
                def jobBuilds = it.getBuilds()
                for (i=0; i < jobBuilds.size(); i++) {
                    def current_job = jobBuilds[i]

                    if (! current_job.getResult().toString().equals("SUCCESS")) continue

                    def current_parameters = current_job.getAction(ParametersAction)?.parameters

                    def current_param_kv = [:]
                    current_parameters.each { param ->
                        current_param_kv[param.name] = param.value
                    }

                    def job_description_lines = current_job.getDescription().split('<br/>')
                    def job_description_map = [:]
                    job_description_lines.each { line ->
                    def _kvlist = line.split(/\:[\s]+/)
                    if (_kvlist.size() == 2) {
                        job_description_map[_kvlist[0].replaceAll(/^[\s]*/,'').replaceAll(/[\s]*$/,'') ] = _kvlist[1].replaceAll(/^[\s]*/,'').replaceAll(/[\s]*$/,'')
                        }
                    }
	                output = current_param_kv + job_description_map
                    output.each { k, v ->
                        output[k] = v.replaceAll(/^"/,'').replaceAll(/"$/,'')
                    }

                    if (is_sub_map(param_filter, output, regex_match)) {
                        println("DEBUG: ${output}")
                        break
                    }
                }
            }// each job
            return output
        }//script
    }//stage
}

def get_build_param_by_name(job_name, param_filter=[:], regex_match=[:]) {
//Get the param of the last success build of a job_name matching the
//param_filter map.
//The regex_match is a dict of 'field_name': true|false where filed_name is in
//the param_filter to state that we should apply regex match on it or not
//It actually like wildcard match - will be appended and prepended with .* to the string
    stage('get_build_param_by_name') {
        script {
            def output = [:]
            def selected_build = null

            Jenkins.instance.getAllItems(Job).findAll() {job -> job.name == job_name}.each{
                def selected_param_kv = [:]
                def jobBuilds = it.getBuilds()
                for (i=0; i < jobBuilds.size(); i++) {
                    def current_job = jobBuilds[i]

                    if (! current_job.getResult().toString().equals("SUCCESS")) continue

                    def current_parameters = current_job.getAction(ParametersAction)?.parameters

                    def current_param_kv = [:]
                    current_parameters.each { param ->
                        current_param_kv[param.name] = param.value
                    }

                    if (is_sub_map(param_filter, current_param_kv, regex_match)) {
                        //Merge values in description to param
                        def job_description_lines = current_job.getDescription().split('<br/>')
                        def job_description_map = [:]
                        job_description_lines.each { line ->
                        	def _kvlist = line.split(/\:[\s]+/)
                            if (_kvlist.size() == 2) {
                                job_description_map[_kvlist[0].replaceAll(/^[\s]*/,'').replaceAll(/[\s]*$/,'') ] = _kvlist[1].replaceAll(/^[\s]*/,'').replaceAll(/[\s]*$/,'')
                            }
                        }
			            output = current_param_kv + job_description_map
                        output.each { k, v ->
                            output[k] = v.replaceAll(/^"/,'').replaceAll(/"$/,'')
                        }
                        break
                    }
                }
            }// each job
            return output
        }//script
    }//stage
}


return this
