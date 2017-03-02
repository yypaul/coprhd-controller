#!/usr/bin/python

# Copyright (c) 2012-13 EMC Corporation
# All Rights Reserved
#
# This software contains the intellectual property of EMC Corporation
# or is licensed to EMC Corporation from third parties.  Use of this
# software and the intellectual property contained therein is expressly
# limited to the terms and conditions of the License Agreement under which
# it is provided by or on behalf of EMC.

import common
import tag
import json
import socket
import commands
from common import SOSError
from threading import Timer
from filepolicy import FilePolicy
import schedulepolicy
import virtualpool
import host


class Fileshare(object):

    '''
    The class definition for operations on 'Fileshare'.
    '''
    # Commonly used URIs for the 'Fileshare' module
    URI_SEARCH_FILESHARES = '/file/filesystems/search?project={0}'
    URI_SEARCH_FILESHARES_BY_PROJECT_AND_NAME = \
        '/file/filesystems/search?project={0}&name={1}'
    URI_FILESHARES = '/file/filesystems'
    URI_FILESHARE = URI_FILESHARES + '/{0}'
    URI_FILESHARE_CREATE = URI_FILESHARES + '?project={0}'
    URI_FILESHARE_SNAPSHOTS = URI_FILESHARE + '/snapshots'
    URI_FILESHARE_RESTORE = URI_FILESHARE + '/restore'
    URI_FILESHARE_EXPORTS = URI_FILESHARE + '/exports'
    URI_FILESHARE_SHOW_EXPORTS = URI_FILESHARE + '/export'
    URI_FILESHARE_SMB_EXPORTS = URI_FILESHARE + '/shares'
    URI_FILESHARE_UNEXPORTS = URI_FILESHARE + '/export'
    URI_FILESHARE_SMB_UNEXPORTS = URI_FILESHARE_SMB_EXPORTS + '/{1}'
    URI_FILESHARE_CONSISTENCYGROUP = URI_FILESHARE + '/consistency-group'
    URI_PROJECT_RESOURCES = '/projects/{0}/resources'
    URI_EXPAND = URI_FILESHARE + '/expand'
    URI_DEACTIVATE = URI_FILESHARE + '/deactivate'
    URI_TAG_FILESHARE = URI_FILESHARE + '/tags'

    URI_UNMANAGED_FILESYSTEM_INGEST = '/vdc/unmanaged/filesystems/ingest'
    URI_UNMANAGED_FILESYSTEM_SHOW = '/vdc/unmanaged/filesystems/{0}'
    URI_CIFS_ACL = URI_FILESHARE + '/shares/{1}/acl'

    URI_TASK_LIST = URI_FILESHARE + '/tasks'
    URI_TASK = URI_TASK_LIST + '/{1}'
    URI_NFS_ACL = '/file/filesystems/{0}/acl'
    
    URI_POLICY_ASSIGN = '/file/filesystems/{0}/assign-file-policy/{1}'
    URI_POLICY_UNASSIGN = '/file/filesystems/{0}/unassign-file-policy/{1}'
    URI_POLICY_LIST = '/file/filesystems/{0}/file-policies'

    URI_CONTINUOS_COPIES_START = '/file/filesystems/{0}/protection/continuous-copies/start'
    URI_CONTINUOS_COPIES_PAUSE = '/file/filesystems/{0}/protection/continuous-copies/pause'
    URI_CONTINUOS_COPIES_RESUME = '/file/filesystems/{0}/protection/continuous-copies/resume'
    URI_CONTINUOS_COPIES_STOP = '/file/filesystems/{0}/protection/continuous-copies/stop'
    URI_CONTINUOS_COPIES_FAILOVER = '/file/filesystems/{0}/protection/continuous-copies/failover'
    URI_CONTINUOS_COPIES_FAILBACK = '/file/filesystems/{0}/protection/continuous-copies/failback'
    URI_CONTINUOS_COPIES_CREATE = '/file/filesystems/{0}/protection/continuous-copies/create'
    URI_CONTINUOS_COPIES_DEACTIVATE = '/file/filesystems/{0}/protection/continuous-copies/deactivate'
    URI_CONTINUOS_COPIES_REFRESH = '/file/filesystems/{0}/protection/continuous-copies/refresh'
    URI_VPOOL_CHANGE = '/file/filesystems/{0}/vpool-change'
    
    URI_SCHEDULE_SNAPSHOTS_LIST = '/file/filesystems/{0}/file-policies/{1}/snapshots'
    
    URI_MOUNT = "/file/filesystems/{0}/mount"
    URI_MOUNT_UNMOUNT = "/file/filesystems/{0}/unmount"
    URI_MOUNT_TASKS_BY_OPID = '/vdc/tasks/{0}'
	
    BOOL_TYPE_LIST = ['true', 'false']
    MOUNT_FS_TYPES = ['auto', 'nfs', 'nfs4']
    MOUNT_SEC_TYPES = ['sys', 'krb5', 'krb5i', 'krb5p']

    isTimeout = False
    timeout = 300

    def __init__(self, ipAddr, port):
        '''
        Constructor: takes IP address and port of the ViPR instance. These are
        needed to make http requests for REST API
        '''
        self.__ipAddr = ipAddr
        self.__port = port

    # Lists fileshares in a project
    def list_fileshares(self, project):
        '''
        Makes REST API call to list fileshares under a project
        Parameters:
            project: name of project
        Returns:
            List of fileshares uuids in JSON response payload
        '''

        from project import Project

        proj = Project(self.__ipAddr, self.__port)
        project_uri = proj.project_query(project)

        fileshare_uris = self.search_fileshares(project_uri)
        fileshares = []
        for uri in fileshare_uris:
            fileshare = self.show_by_uri(uri)
            if(fileshare):
                fileshares.append(fileshare)
        return fileshares

    '''
    Given the project name and volume name, the search will be performed to
    find  if the fileshare with the given name exists or not.
    If found, the uri of the fileshare will be returned
    '''

    def search_by_project_and_name(self, projectName, fileshareName):

        return (
            common.search_by_project_and_name(
                projectName, fileshareName,
                Fileshare.URI_SEARCH_FILESHARES_BY_PROJECT_AND_NAME,
                self.__ipAddr, self.__port)
        )

    def search_fileshares(self, project_uri, fileshare=None):

        uri = Fileshare.URI_SEARCH_FILESHARES.format(project_uri)
        if (fileshare is not None):
            uri = Fileshare.URI_SEARCH_FILESHARES_BY_PROJECT_AND_NAME.format(
                project_uri, fileshare)
        
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "GET", uri, None)
        o = common.json_decode(s)
        if not o:
            return []

        fileshare_uris = []
        resources = common.get_node_value(o, "resource")
        for resource in resources:
            fileshare_uris.append(resource["id"])
        return fileshare_uris

    # Get the list of fileshares given a project uri
    def list_by_uri(self, project_uri):
        '''
        Makes REST API call and retrieves fileshares based on project UUID
        Parameters:
            project_uri: UUID of project
        Returns:
            List of fileshare UUIDs in JSON response payload
        '''

        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port, "GET",
            Fileshare.URI_PROJECT_RESOURCES.format(project_uri), None)
        o = common.json_decode(s)
        if not o:
            return []

        fileshare_uris = []
        resources = common.get_node_value(o, "project_resource")
        for resource in resources:
            if(resource["resource_type"] == "fileshare"):
                fileshare_uris.append(resource["id"])
        return fileshare_uris

    # Shows fileshare information given its name
    def show(self, name, show_inactive=False, xml=False):
        '''
        Retrieves fileshare details based on fileshare name
        Parameters:
            name: name of the fileshare. If the fileshare is under a project,
            then full XPath needs to be specified.
            Example: If FS1 is a fileshare under project PROJ1, then the name
            of fileshare is PROJ1/FS1
        Returns:
            Fileshare details in JSON response payload
        '''

        from project import Project

        if (common.is_uri(name)):
            return name

        (pname, label) = common.get_parent_child_from_xpath(name)
        if(not pname):
            raise SOSError(SOSError.NOT_FOUND_ERR,
                           "Filesystem " + name + ": not found")

        proj = Project(self.__ipAddr, self.__port)
        puri = proj.project_query(pname)

        puri = puri.strip()
        uris = self.search_fileshares(puri, label)
        
        if (len(uris) > 0):
            fileshare = self.show_by_uri(uris[0], show_inactive)
            if(not xml):
                return fileshare
            else:
                return self.show_by_uri(fileshare['id'], show_inactive, xml)
            
        raise SOSError(SOSError.NOT_FOUND_ERR,
                       "Filesystem " + label + ": not found")

    # Shows fileshare information given its uri
    def show_by_uri(self, uri, show_inactive=False, xml=False):
        '''
        Makes REST API call and retrieves fileshare details based on UUID
        Parameters:
            uri: UUID of fileshare
        Returns:
            Fileshare details in JSON response payload
        '''
        if(xml):
            (s, h) = common.service_json_request(
                self.__ipAddr, self.__port,
                "GET", Fileshare.URI_FILESHARE.format(uri),
                None, None, xml)
            return s

        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "GET", Fileshare.URI_FILESHARE.format(uri), None)
        if(not s):
            return None
        o = common.json_decode(s)
        if(show_inactive):
            return o
        if('inactive' in o):
            if(o['inactive']):
                return None
        return o

    def unmanaged_filesystem_ingest(self, tenant, project,
                                    varray, vpool, filesystems):
        '''
        This function is to ingest given unmanaged filesystems
        into ViPR.
        '''
        from project import Project
        proj_obj = Project(self.__ipAddr, self.__port)
        project_uri = proj_obj.project_query(tenant + "/" + project)

        from virtualpool import VirtualPool
        vpool_obj = VirtualPool(self.__ipAddr, self.__port)
        vpool_uri = vpool_obj.vpool_query(vpool, "file")

        from virtualarray import VirtualArray
        varray_obj = VirtualArray(self.__ipAddr, self.__port)
        varray_uri = varray_obj.varray_query(varray)

        request = {
            'vpool': vpool_uri,
            'varray': varray_uri,
            'project': project_uri,
            'unmanaged_filesystem_list': filesystems
        }

        body = json.dumps(request)
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "POST",
            Fileshare.URI_UNMANAGED_FILESYSTEM_INGEST,
            body)
        o = common.json_decode(s)
        return o

    def unmanaged_filesystem_show(self, filesystem):
        '''
        This function is to show the details of unmanaged filesystem
        from  ViPR.
        '''
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "GET",
            Fileshare.URI_UNMANAGED_FILESYSTEM_SHOW.format(filesystem),
            None)
        o = common.json_decode(s)
        return o

    # Creates a fileshare given label, project, vpool, size and id for vnx file
    def create(self, project, label, size, varray, vpool, id, protocol, sync, advlim, softlim, grace,synctimeout):
        '''
        Makes REST API call to create fileshare under a project
        Parameters:
            project: name of the project under which the fileshare will
                     be created
            label: name of fileshare
            size: size of fileshare
            varray: name of varray
            vpool: name of vpool
            id : id of fileshare applicatble for VNX File
            protocol: NFS, NFSv4, CIFS
        Returns:
            Created task details in JSON response payload
        '''

        from virtualpool import VirtualPool
        from project import Project
        from virtualarray import VirtualArray

        vpool_obj = VirtualPool(self.__ipAddr, self.__port)
        vpool_uri = vpool_obj.vpool_query(vpool, "file")

        varray_obj = VirtualArray(self.__ipAddr, self.__port)
        varray_uri = varray_obj.varray_query(varray)

        parms = {
            'name': label,
            'size': size,
            'varray': varray_uri,
            'vpool': vpool_uri,
            'fs_id' : id,
            'soft_limit' : softlim,
            'soft_grace' : grace,
            'notification_limit' : advlim
        }

        if(protocol):
            parms["protocols"] = protocol

        body = json.dumps(parms)

        proj = Project(self.__ipAddr, self.__port)
        project_uri = proj.project_query(project)

        try:
            (s, h) = common.service_json_request(
                self.__ipAddr, self.__port, "POST",
                Fileshare.URI_FILESHARE_CREATE.format(project_uri),
                body)
            o = common.json_decode(s)
            if(sync):
                #fileshare = self.show(name, True)
                return self.check_for_sync(o, sync,synctimeout)
            
            else:
                return o
        except SOSError as e:
            errorMessage = str(e).replace(vpool_uri, vpool)
            errorMessage = errorMessage.replace(varray_uri, varray)
            common.format_err_msg_and_raise("create", "filesystem",
                                            errorMessage, e.err_code)

    # Tag the fileshare information
    def tag(self, name, add, remove):
        '''
        Makes REST API call to update a fileshare information
        Parameters:
            name: name of the fileshare to be updated
            add: tags to be added
            remove: tags to be removed
        Returns
            Created task details in JSON response payload
        '''
        fileshare_uri = self.fileshare_query(name)

        return (
            tag.tag_resource(self.__ipAddr, self.__port,
                             Fileshare.URI_TAG_FILESHARE,
                             fileshare_uri, add, remove)
        )

    #Update a fileshare information
    def update(self, name, advlim, softlim, grace):
        '''
        Makes REST API call to update a fileshare information
        Parameters:
            name: name of the fileshare to be updated
        Returns
            Created task details in JSON response payload
        '''
        
        fileshare_uri = self.fileshare_query(name)

        parms = dict()

        if advlim is not None :
            parms['notification_limit'] = advlim
        if softlim is not None :
            parms['soft_limit'] = softlim
        if grace is not None :
            parms['soft_grace'] = grace
            
        body = json.dumps(parms)

        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port, "PUT",
            Fileshare.URI_FILESHARE.format(fileshare_uri), body)
        o = common.json_decode(s)
        return o

    # Exports a fileshare to a host given a fileshare name and the host name
    def export(
            self, name, security_type, permission, root_user,
            endpoints, protocol, share_name, share_description,
            permission_type, sub_dir, sync,synctimeout):
        '''
        Makes REST API call to export fileshare to a host
        Parameters:
            name: name of fileshare
            type: type of security
            permission: Permissions
            root_user: root user
            endpoints: host names, IP addresses, or netgroups
            protocol:  NFS, NFSv4, CIFS
            share_name: Name of the SMB share
            share_description: Description of SMB share
        Returns:
            Created Operation ID details in JSON response payload
        '''
        fileshare_uri = name
        try:
            fileshare_uri = self.fileshare_query(name)
            if(protocol == 'CIFS'):
                request = {
                    'name': share_name,
                    'description': share_description
                }
                if(sub_dir):
                    request["subDirectory"] = sub_dir
                

                if(permission_type):
                    request["permission_type"] = permission_type
                if(permission and permission in ["read", "change", "full"]):
                    request["permission"] = permission

                body = json.dumps(request)

                (s, h) = common.service_json_request(
                    self.__ipAddr, self.__port, "POST",
                    Fileshare.URI_FILESHARE_SMB_EXPORTS.format(fileshare_uri),
                    body)

            else:
                request = {
                    'type': security_type,
                    'permissions': permission,
                    'root_user': root_user,
                    'endpoints': endpoints,
                    'protocol': protocol,
                }
                if(sub_dir):
                    request["sub_directory"] = sub_dir
                

                body = json.dumps(request)
                (s, h) = common.service_json_request(
                    self.__ipAddr, self.__port, "POST",
                    Fileshare.URI_FILESHARE_EXPORTS.format(fileshare_uri),
                    body)
            if(not s):
                return None
            o = common.json_decode(s)
            if(sync):
                return self.check_for_sync(o, sync,synctimeout)
            else:
                return o
        except SOSError as e:
            errorMessage = str(e)
            if(common.is_uri(fileshare_uri)):
                errorMessage = str(e).replace(fileshare_uri, name)
            common.format_err_msg_and_raise("export", "filesystem",
                                            errorMessage, e.err_code)

    # Unexports a fileshare from a host given a fileshare name, type of
    # security and permission
    def unexport(self, name, protocol, share_name, sub_dir, all_dir, sync,synctimeout):
        '''
        Makes REST API call to unexport fileshare from a host
        Parameters:
            name: name of fileshare
            security_type: type of security
            permission: Permissions
            root_user: root_user mapping
            protocol: NFS, NFSv4, CIFS
        Returns:
            Created Operation ID details in JSON response payload
        '''

        fileshare_uri = self.fileshare_query(name)
        if(protocol == 'CIFS'):
            (s, h) = common.service_json_request(
                self.__ipAddr, self.__port, "DELETE",
                Fileshare.URI_FILESHARE_SMB_UNEXPORTS.format(fileshare_uri,
                                                             share_name),
                None)
        else:
            request_uri = Fileshare.URI_FILESHARE_UNEXPORTS.format(fileshare_uri)
            if(sub_dir):
                request_uri = request_uri + "?subDir=" + sub_dir
            elif(all_dir):
                request_uri = request_uri + "?allDirs=true"
            (s, h) = common.service_json_request(
                self.__ipAddr, self.__port, "DELETE",
                request_uri, None)
        if(not s):
            return None
        o = common.json_decode(s)
        if(sync):
            return self.check_for_sync(o, sync,synctimeout)
        else:
            return o


    def export_rule(self, name, operation, securityflavor, user=None, roothosts=None, readonlyhosts=None, readwritehosts=None, subDir=None):
        
        fileshare_uri = self.fileshare_query(name)
        exportRulesparam = dict()
        exportRulesparam['secFlavor'] = securityflavor
        if(roothosts):
            exportRulesparam['rootHosts'] = roothosts
        if(readonlyhosts):
            exportRulesparam['readOnlyHosts'] = readonlyhosts
        if(readwritehosts):
            exportRulesparam['readWriteHosts'] = readwritehosts
        if(user):
            exportRulesparam['anon'] = user

        exportRulerequest = {'exportRules':[exportRulesparam]}

        if("add"== operation):
            request = {'add': exportRulerequest}
        elif("delete" == operation):
            request = {'delete' : exportRulerequest}
        else:
            request = {'modify' : exportRulerequest}
    
        body = json.dumps(request)
        params = ''
        if(subDir):
            params += '&' if ('?' in params) else '?'
            params += "subDir=" + subDir

        (s, h) = common.service_json_request(self.__ipAddr, self.__port, "PUT", Fileshare.URI_FILESHARE_SHOW_EXPORTS.format(fileshare_uri) + params, body)
        o = common.json_decode(s)
        return o



    # Deletes a fileshare given a fileshare name
    def delete(self, name, forceDelete=False, delete_type='FULL', sync=False,synctimeout=0):
        '''
        Deletes a fileshare based on fileshare name
        Parameters:
            name: name of fileshare
        '''
        fileshare_uri = self.fileshare_query(name)
        return self.delete_by_uri(fileshare_uri, forceDelete, delete_type, sync,synctimeout)
    
        # Deletes a fileshare given a fileshare name
    def delete_acl(self, name, sharename):
        '''
        Deletes a fileshare based on fileshare name
        Parameters:
            name: name of fileshare
        
        '''
        
        fileshare_uri = self.fileshare_query(name)
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port, "DELETE",
            Fileshare.URI_CIFS_ACL.format(fileshare_uri, sharename),
            None)
        return 
    
    def list_acl(self, name, sharename):
        '''
        Deletes a fileshare based on fileshare name
        Parameters:
            name: name of fileshare
        '''
        fileshare_uri = self.fileshare_query(name)
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port, "GET",
            Fileshare.URI_CIFS_ACL.format(fileshare_uri, sharename),
            None)
        o = common.json_decode(s)
        return o
    
    #Delete and List NFS ACL Routines
    
    def nfs_acl_delete(self, name, subdir):
        '''
        Delete filesystem acls or filesystem subdir
        Parameters:
            name: name of fileshare
            subdir:name of subdirectory
        
        '''
        uri_nfs_qp = Fileshare.URI_NFS_ACL
        if(subdir is not None):
            uri_nfs_qp = Fileshare.URI_NFS_ACL + '?' + "subDir=" + subdir
        
        fileshare_uri = self.fileshare_query(name)
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port, "DELETE",
            uri_nfs_qp.format(fileshare_uri),
            None)
        return 
    
    def nfs_acl_list(self, name, alldir, subdir):
        '''
        List the acl's of a fileshare based on fileshare name
        Parameters:
            name: name of fileshare
            alldir : list the acls for all the directories
            subdir : list the acl's of a particular subdirectory
            
        '''
        uri_nfs_qp = Fileshare.URI_NFS_ACL
        if(alldir == True):
            uri_nfs_qp = Fileshare.URI_NFS_ACL + '?' + "allDirs=true"
        if(subdir is not None):
            uri_nfs_qp = Fileshare.URI_NFS_ACL + '?' + "subDir=" + subdir
        fileshare_uri = self.fileshare_query(name)

        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port, "GET",
            uri_nfs_qp.format(fileshare_uri),
            None)
        o = common.json_decode(s)
        return o
    
    # update cifs acl for given share    
    def cifs_acl(self, tenant, project, fsname, sharename, operation, user=None, permission=None, domain=None, group=None):
        path = tenant + "/" + project + "/"
        fs_name = path + fsname

        
        fs_uri = self.fileshare_query(fs_name)        
        #share_uri = self.fileshare_query(sh_name)
        
        cifs_acl_param = dict()
        cifs_acl_param['share_name'] = sharename
        if(permission):
            cifs_acl_param['permission'] = permission
        if(user):
            cifs_acl_param['user'] = user
        if(domain):
            cifs_acl_param['domain'] = domain
        if(group):
            cifs_acl_param['group'] = group

        acl_cifs_request = {'acl':[cifs_acl_param]}

        if("add"== operation):
            request = {'add': acl_cifs_request}
        elif("delete" == operation):
            request = {'delete' : acl_cifs_request}
        else:
            request = {'modify' : acl_cifs_request}
    
        body = json.dumps(request)
        
        (s, h) = common.service_json_request(
                    self.__ipAddr, self.__port, 
                    "PUT", 
                    Fileshare.URI_CIFS_ACL.format(fs_uri, sharename) , body)
        o = common.json_decode(s)
        return o
    
    
    #Main routine for NFSv4 ACL
    def nfs_acl(self, tenant, project, fsname, subdir, permissiontype, type, operation, user=None, permission=None, domain=None, group=None):
        path = tenant + "/" + project + "/"
        fs_name = path + fsname
        
        
        fs_uri = self.fileshare_query(fs_name)        
        
        
        nfs_acl_param = dict()
        if(permissiontype):
            nfs_acl_param['permission_type'] = permissiontype
        if(permission):
            nfs_acl_param['permissions'] = permission
        if(user):
            nfs_acl_param['user'] = user
        if(domain):
            nfs_acl_param['domain'] = domain
        if(type):
            nfs_acl_param['type'] = type
            
        request = dict()
        
        if("add" == operation):
            request = {'add': [nfs_acl_param] }
        if("delete" == operation):
            request = {'delete' : [nfs_acl_param]}
        if("update" == operation):
            request = {'modify' : [nfs_acl_param]}
        if(subdir):
            request['subDir']= subdir 
        body = json.dumps(request)
        
        (s, h) = common.service_json_request(
                    self.__ipAddr, self.__port, 
                    "PUT", 
                    Fileshare.URI_NFS_ACL.format(fs_uri) , body)
        o = common.json_decode(s)
        return o



    # Deletes a fileshare given a fileshare uri
    def delete_by_uri(self, uri, forceDelete=False, delete_type='FULL', sync=False,synctimeout=0):
        '''
        Deletes a fileshare based on fileshare uri
        Parameters:
            uri: uri of fileshare
        '''
        request = {"forceDelete": forceDelete,"delete_type": delete_type}
        body = json.dumps(request)
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "POST",
            Fileshare.URI_DEACTIVATE.format(uri),
            body)
        if(not s):
            return None
        o = common.json_decode(s)
        if(sync):
            return self.check_for_sync(o, sync,synctimeout)
        return o

    def get_exports_by_uri(self, uri, subDir=None, allDir=None):
        '''
        Get a fileshare export based on fileshare uri
        Parameters:
            uri: uri of fileshare
        '''
        params = ''
        if (subDir):
            params += '&' if ('?' in params) else '?'
            params += "subDir=" + subDir
        elif (allDir):
            params += '&' if ('?' in params) else '?'
            params += "allDir=" + "true"
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "GET",
            Fileshare.URI_FILESHARE_SHOW_EXPORTS.format(uri) + params,
            None)
        if(not s):
            return None
        o = common.json_decode(s)
        if(not o):
            return None
        return o

    def get_exports(self, name, subDir, allDir):
        '''
        Get a fileshare export based on fileshare name
        Parameters:
            name: name of fileshare
        '''
        fileshare_uri = self.fileshare_query(name)
        return self.get_exports_by_uri(fileshare_uri, subDir, allDir)

    def get_shares_by_uri(self, uri):
        '''
        Get a fileshare export based on fileshare uri
        Parameters:
            uri: uri of fileshare
        '''
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "GET",
            Fileshare.URI_FILESHARE_SMB_EXPORTS.format(uri),
            None)
        if (not s):
            return None
        o = common.json_decode(s)
        if(not o):
            return None
        return o

    def get_shares(self, name):
        '''
        Get a fileshare shares based on fileshare name
        Parameters:
            name: name of fileshare
        '''
        fileshare_uri = self.fileshare_query(name)
        return self.get_shares_by_uri(fileshare_uri)

    # Queries a fileshare given its name
    def fileshare_query(self, name):
        '''
        Makes REST API call to query the fileshare by name
        Parameters:
            name: name of fileshare
        Returns:
            Fileshare details in JSON response payload
        '''

        from project import Project
        if (common.is_uri(name)):
            return name
        (pname, label) = common.get_parent_child_from_xpath(name)
        if(not pname):
            raise SOSError(SOSError.NOT_FOUND_ERR,
                           "Project name  not specified")

        proj = Project(self.__ipAddr, self.__port)
        puri = proj.project_query(pname)
        puri = puri.strip()
        uris = self.search_fileshares(puri)
        for uri in uris:
            fileshare = self.show_by_uri(uri)
            if (fileshare and fileshare['name'] == label):
                return fileshare['id']
        raise SOSError(SOSError.NOT_FOUND_ERR,
                       "Filesystem " + label + ": not found")

    # Timeout handler for synchronous operations
    def timeout_handler(self):
        self.isTimeout = True

    # Blocks the opertaion until the task is complete/error out/timeout
    def check_for_sync(self, result, sync, synctimeout=0):
        if(sync):
            if 'resource' in result :
                if(len(result["resource"]) > 0):
                    resource = result["resource"]
                    return (
                        common.block_until_complete("fileshare", resource["id"],
                                                    result["id"], self.__ipAddr,
                                                    self.__port,synctimeout)
                    )
                else:
                    raise SOSError(
                        SOSError.SOS_FAILURE_ERR,
                        "error: task list is empty, no task response found")
        else:
            return result
        
    
    # Blocks the replication operation until the task is complete/error out/timeout
    def check_for_sync_replication(self, result, sync, synctimeout=0):
        if(sync):
            if 'task' in result :
                task = result['task']
                task_element = task[0]
                if(len(task_element['resource']) > 0):
                    resource = task_element['resource']
                    return (
                        common.block_until_complete("fileshare", resource["id"],
                                                    task_element['id'], self.__ipAddr,
                                                    self.__port,synctimeout)
                    )
                else:
                    raise SOSError(
                        SOSError.SOS_FAILURE_ERR,
                        "error: task list is empty, no task response found")
        else:
            return result

    def list_tasks(self, project_name, fileshare_name=None, task_id=None):
        return (
            common.list_tasks(self.__ipAddr, self.__port, "fileshare",
                              project_name, fileshare_name, task_id)
        )
    
    def expand(self, name, new_size, sync=False,synctimeout=0):

        fileshare_detail = self.show(name)
        current_size = float(fileshare_detail["capacity_gb"])

        if(new_size <= current_size):
            raise SOSError(
                SOSError.VALUE_ERR,
                "error: Incorrect value of new size: " + str(new_size) +
                " bytes\nNew size must be greater than current size: " +
                str(current_size) + " bytes")

        body = json.dumps({
            "new_size": new_size
        })

        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "POST",
            Fileshare.URI_EXPAND.format(fileshare_detail["id"]),
            body)
        if(not s):
            return None
        o = common.json_decode(s)
        if(sync):
            return self.check_for_sync(o, sync,synctimeout)
        return o
    
    def assign_policy(self, filesharename, policyname, tenantname, policyid, targetvarrays):
        assign_request = {}
        trg_varrays = []
        if (targetvarrays is not None):
            assign_target_varrays = []
            from virtualarray import VirtualArray
            varray_obj = VirtualArray(self.__ipAddr, self.__port)
            if( len(targetvarrays)>1):
                trg_varrays= targetvarrays.split(',')
                for varray in trg_varrays:
                    uri =  varray_obj.varray_query(varray)
                    assign_target_varrays.append(uri)
            else:
                uri = varray_obj.varray_query(targetvarrays)
                assign_target_varrays.append(uri)
            assign_request['target_varrays']= assign_target_varrays
            
        fsname = self.show(filesharename)
        fsid = fsname['id']

        body = json.dumps(assign_request)
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "PUT",
            Fileshare.URI_POLICY_ASSIGN.format(fsid, policyid),
            body)
        
        return
    
    def unassign_policy(self, filesharename, policyname, tenantname, policyid):
        fsname = self.show(filesharename)
        fsid = fsname['id']
        
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "PUT",
            Fileshare.URI_POLICY_UNASSIGN.format(fsid, policyid),
            None)
        
        return
    
    def policy_list(self, filesharename):
        fsname = self.show(filesharename)
        fsid = fsname['id']
        
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "GET",
            Fileshare.URI_POLICY_LIST.format(fsid),
            None)
        
        res = common.json_decode(s)
        return res['file_policy']

    def continous_copies_start(self, filesharename, sync, synctimeout=0):
        fsname = self.show(filesharename)
        fsid = fsname['id']
        copy_dict = {
                     'type' : "REMOTE_MIRROR"}
        copy_list = []
        copy_list.append(copy_dict)
        parms = {
                 'file_copy' : copy_list}
        
        body = None

        body = json.dumps(parms)
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "POST",
            Fileshare.URI_CONTINUOS_COPIES_START.format(fsid),
            body)
        o = common.json_decode(s)
        
        if(sync):
            return self.check_for_sync_replication(o, sync, synctimeout)
        else:
            return
    
    def continous_copies_pause(self, filesharename, sync, synctimeout=0):
        fsname = self.show(filesharename)
        fsid = fsname['id']
        copy_dict = {
                     'type' : "REMOTE_MIRROR"}
        copy_list = []
        copy_list.append(copy_dict)
        parms = {
                 'file_copy' : copy_list}
        
        body = None

        body = json.dumps(parms)

        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "POST",
            Fileshare.URI_CONTINUOS_COPIES_PAUSE.format(fsid),
            body)
        
        o = common.json_decode(s)

        if(sync):
            return self.check_for_sync_replication(o, sync, synctimeout)
        else:
            return
    
    def continous_copies_resume(self, filesharename, sync, synctimeout=0):
        fsname = self.show(filesharename)
        fsid = fsname['id']
        copy_dict = {
                     'type' : "REMOTE_MIRROR"}
        copy_list = []
        copy_list.append(copy_dict)
        parms = {
                 'file_copy' : copy_list}
        
        body = None

        body = json.dumps(parms)

        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "POST",
            Fileshare.URI_CONTINUOS_COPIES_RESUME.format(fsid),
            body)
        
        o = common.json_decode(s)

        if(sync):
            return self.check_for_sync_replication(o, sync, synctimeout)
        else:
            return
    
    def continous_copies_stop(self, filesharename, sync, synctimeout=0):
        fsname = self.show(filesharename)
        fsid = fsname['id']
        copy_dict = {
                     'type' : "REMOTE_MIRROR"}
        copy_list = []
        copy_list.append(copy_dict)
        parms = {
                 'file_copy' : copy_list}
        
        body = None

        body = json.dumps(parms)

        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "POST",
            Fileshare.URI_CONTINUOS_COPIES_STOP.format(fsid),
            body)
        
        o = common.json_decode(s)

        if(sync):
            return self.check_for_sync_replication(o, sync, synctimeout)
        else:
            return
    
    def continous_copies_failover(self, filesharename, replicateconfig,sync, synctimeout=0):
        fsname = self.show(filesharename)
        fsid = fsname['id']
        copy_dict = {
                     'type' : "REMOTE_MIRROR"}
        copy_list = []
        copy_list.append(copy_dict)
        parms = {
                 'file_copy' : copy_list,
				 'replicate_configuration' : replicateconfig}
        
        body = None

        body = json.dumps(parms)

        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "POST",
            Fileshare.URI_CONTINUOS_COPIES_FAILOVER.format(fsid),
            body)
        
        o = common.json_decode(s)

        if(sync):
            return self.check_for_sync_replication(o, sync, synctimeout)
        else:
            return
    
    def continous_copies_failback(self, filesharename, replicateconfig, sync, synctimeout=0):
        fsname = self.show(filesharename)
        fsid = fsname['id']
        copy_dict = {
                     'type' : "REMOTE_MIRROR"}
        copy_list = []
        copy_list.append(copy_dict)
        parms = {
                 'file_copy' : copy_list,
				 'replicate_configuration' : replicateconfig}
        
        body = None

        body = json.dumps(parms)

        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "POST",
            Fileshare.URI_CONTINUOS_COPIES_FAILBACK.format(fsid),
            body)
        
        o = common.json_decode(s)

        if(sync):
            return self.check_for_sync_replication(o, sync, synctimeout)
        else:
            return
    
    def continous_copies_create(self, filesharename, sync, targetname=None, synctimeout=0):
        fsname = self.show(filesharename)
        fsid = fsname['id']
        parms = {
                     'copy_name' : targetname,
                     'type' : "REMOTE_MIRROR"}

        body = json.dumps(parms)
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "POST",
            Fileshare.URI_CONTINUOS_COPIES_CREATE.format(fsid),
            body)
        
        o = common.json_decode(s)

        if(sync):
            return self.check_for_sync_replication(o, sync, synctimeout)
        else:
            return
    
    def continous_copies_deactivate(self, filesharename, sync, synctimeout=0):
        fsname = self.show(filesharename)
        fsid = fsname['id']
        parms = {
                     'delete_type' : "FULL"}

        body = json.dumps(parms)
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "POST",
            Fileshare.URI_CONTINUOS_COPIES_DEACTIVATE.format(fsid),
            body)
        
        o = common.json_decode(s)

        if(sync):
            return self.check_for_sync_replication(o, sync, synctimeout)
        else:
            return
    
    def continous_copies_refresh(self, filesharename, sync, synctimeout=0):
        fsname = self.show(filesharename)
        fsid = fsname['id']
        copy_dict = {
                     'type' : "REMOTE_MIRROR"}
        copy_list = []
        copy_list.append(copy_dict)
        parms = {
                 'file_copy' : copy_list}

        body = json.dumps(parms)
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "POST",
            Fileshare.URI_CONTINUOS_COPIES_REFRESH.format(fsid),
            body)
        
        o = common.json_decode(s)

        if(sync):
            return self.check_for_sync_replication(o, sync, synctimeout)
        else:
            return
    
    def change_vpool(self, filesharename, vpoolid):
        fsname = self.show(filesharename)
        fsid = fsname['id']
         
        parms = {
                 'vpool' : vpoolid}
        

        body = json.dumps(parms)
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "PUT",
            Fileshare.URI_VPOOL_CHANGE.format(fsid),
            body)

        return
    
    def schedule_snapshots_list(self, filesharename, policyname, tenantname, policyid):
        fsname = self.show(filesharename)
        fsid = fsname['id']
        
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "GET",
            Fileshare.URI_SCHEDULE_SNAPSHOTS_LIST.format(fsid, policyid),
            None)
        
        o = common.json_decode(s)
        return    

    def mount(self, ouri, hosturi, subdirectory, securitystyle, path, fstype, sync, synctimeout):
        parms = {
        }
        if path:
            parms["path"] = path
        if securitystyle:
            parms["security"] = securitystyle
        if subdirectory:
            parms['sub_directory'] = subdirectory
        if hosturi:
            parms['host'] = hosturi
        if fstype:
            parms['fs_type'] = fstype
            
        body = json.dumps(parms)

        # REST api call
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "POST",
            Fileshare.URI_MOUNT.format(ouri), body)

        o = common.json_decode(s)
        if(sync):
            return self.check_for_sync(o, sync, synctimeout)
        else:
            return o
        
    def unmount(self, ouri, hosturi, mountpath, sync, synctimeout):
        body = None    
        params = dict()
        if hosturi:
            params['host'] = hosturi
        if mountpath:
            params["mount_path"] = mountpath
     
        body = json.dumps(params)

        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "POST",
            Fileshare.URI_MOUNT_UNMOUNT.format(ouri), body)

        o = common.json_decode(s)

        if(sync):
            return self.check_for_sync(o, sync,synctimeout)
        else:
            return o

    def mount_list(self, resourceUri):
        if resourceUri is not None:
            return self.mount_list_uri(resourceUri)
        return None

    def mount_list_uri(self, ouri):
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "GET",
            Fileshare.URI_MOUNT.format(ouri), None)
        o = common.json_decode(s)
        return o['mount_info']
    
    def storageResource_query(self,
                              fileshareName,
                              project,
                              tenant):
        resourcepath = "/" + project + "/"
        if tenant is not None:
            resourcepath = tenant + resourcepath
        resourceObj = None
        resourceObj = Fileshare(self.__ipAddr, self.__port)
        return resourceObj.fileshare_query(resourcepath + fileshareName)
    
    def mount_show_task_opid(self, taskid):
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "GET",
            Fileshare.URI_MOUNT_TASKS_BY_OPID.format(taskid),
            None)
        if not s:
            return None
        o = common.json_decode(s)
        return o

# Fileshare Create routines


def create_parser(subcommand_parsers, common_parser):
    create_parser = subcommand_parsers.add_parser(
        'create',
        description='ViPR Filesystem Create CLI usage.',
        parents=[common_parser], conflict_handler='resolve',
        help='Create a filesystem')
    mandatory_args = create_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                metavar='<filesystemname>',
                                dest='name',
                                help='Name of Filesystem',
                                required=True)
    mandatory_args.add_argument(
        '-size', '-s',
        help='Size of filesystem: {number}[unit]. ' +
        'A size suffix of K for kilobytes, M for megabytes, ' +
        'G for  gigabytes, T  for terabytes is optional.' +
        'Default unit is bytes.',
        metavar='<filesharesize[kKmMgGtT]>',
        dest='size',
        required=True)
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of Project',
                                required=True)
    create_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')
    mandatory_args.add_argument('-vpool', '-vp',
                                metavar='<vpoolname>', dest='vpool',
                                help='Name of vpool',
                                required=True)
    mandatory_args.add_argument('-varray', '-va',
                                help='Name of varray',
                                metavar='<varray>',
                                dest='varray',
                                required=True)
    create_parser.add_argument('-id', '-id',
                                help='Optional Id for VNX FileSystem',
                                metavar='<filesystemid>',
                                dest='id',
                                required=False)
    create_parser.add_argument('-advisorylimit', '-advlmt',
                               dest='advlim',
                               help='Advisory limit in percentage for the filesystem',
                               metavar='<advisorylimit>')
    create_parser.add_argument('-softlimit', '-softlmt',
                               dest='softlim',
                               help='Soft limit in percentage for the filesystem',
                               metavar='<softlimit>')
    create_parser.add_argument('-graceperiod', '-grace',
                               dest='grace',
                               help='Grace period in days for soft limit',
                               metavar='<graceperiod>')
    create_parser.add_argument('-synchronous', '-sync',
                               dest='sync',
                               help='Execute in synchronous mode',
                               action='store_true')

    create_parser.add_argument('-synctimeout','-syncto',
                               help='sync timeout in seconds ',
                               dest='synctimeout',
                               default=0,
                               type=int)
    
    create_parser.set_defaults(func=fileshare_create)


def fileshare_create(args):
    if not args.sync and args.synctimeout !=0:
        raise SOSError(SOSError.CMD_LINE_ERR,"error: Cannot use synctimeout without Sync ")
    size = common.to_bytes(args.size)
    if not size:
        raise SOSError(SOSError.CMD_LINE_ERR,
                       'error: Invalid input for -size')
    if(not args.tenant):
        args.tenant = ""
    try:
        obj = Fileshare(args.ip, args.port)
        res = obj.create(args.tenant + "/" + args.project,
                         args.name,
                         size,
                         args.varray,
                         args.vpool,
                         args.id,
                         None,
                         args.sync,
                         args.advlim,
                         args.softlim,
                         args.grace,args.synctimeout)
#        if(args.sync == False):
#            return common.format_json_object(res)
    except SOSError as e:
        if (e.err_code in [SOSError.NOT_FOUND_ERR,
                           SOSError.ENTRY_ALREADY_EXISTS_ERR]):
            raise SOSError(e.err_code, "Create failed: " + e.err_text)
        else:
            raise e

# fileshare Update routines


'''def update_parser(subcommand_parsers, common_parser):
    update_parser = subcommand_parsers.add_parser(
        'update',
        description='ViPR Filesystem Update CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Update a filesystem')
    mandatory_args = update_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                help='Name of filesystem',
                                metavar='<filesystemname>',
                                dest='name',
                                required=True)
    update_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    mandatory_args.add_argument('-label', '-l',
                                help='New label of filesystem',
                                metavar='<label>',
                                dest='label',
                                required=True)
    mandatory_args.add_argument('-vpool', '-vp',
                                help='Name of New vpool',
                                metavar='<vpoolname>',
                                dest='vpool',
                                required=True)

    update_parser.set_defaults(func=fileshare_update)


def fileshare_update(args):
    if(not args.tenant):
        args.tenant = ""

    try:
        obj = Fileshare(args.ip, args.port)
        res = obj.update(args.tenant + "/" + args.project + "/" + args.name,
                         args.label,
                         args.vpool)
    except SOSError as e:
        if (e.err_code == SOSError.NOT_FOUND_ERR):
            raise SOSError(e.err_code, "Update failed: " + e.err_text)
        else:
            raise e'''
       
       

def update_parser(subcommand_parsers, common_parser):
    update_parser = subcommand_parsers.add_parser(
        'update',
        description='ViPR Filesystem Update CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Update a filesystem')
    mandatory_args = update_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                help='Name of filesystem',
                                metavar='<filesystemname>',
                                dest='name',
                                required=True)
    update_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    update_parser.add_argument('-advisorylimit', '-advlmt',
                               dest='advlim',
                               help='Advisory limit in percentage for the filesystem',
                               metavar='<advisorylimit>')
    update_parser.add_argument('-softlimit', '-softlmt',
                               dest='softlim',
                               help='Soft limit in percentage for the filesystem',
                               metavar='<softlimit>')
    update_parser.add_argument('-graceperiod', '-grace',
                               dest='grace',
                               help='Grace period in days for soft limit',
                               metavar='<graceperiod>')
    
    update_parser.set_defaults(func=fileshare_update)


def fileshare_update(args):
    if(not args.tenant):
        args.tenant = ""

    try:
        obj = Fileshare(args.ip, args.port)
        res = obj.update(args.tenant + "/" + args.project + "/" + args.name,
                         args.advlim,
                         args.softlim,
                         args.grace)
    except SOSError as e:
        if (e.err_code == SOSError.NOT_FOUND_ERR):
            raise SOSError(e.err_code, "Update failed: " + e.err_text)
        else:
            raise e       



# Fileshare Delete routines

def delete_parser(subcommand_parsers, common_parser):
    delete_parser = subcommand_parsers.add_parser(
        'delete',
        description='ViPR Filesystem Delete CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Delete a filesystem')
    mandatory_args = delete_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                metavar='<filesystemname>',
                                dest='name',
                                help='Name of Filesystem',
                                required=True)
    delete_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    delete_parser.add_argument('-synchronous', '-sync',
                               dest='sync',
                               help='Execute in synchronous mode',
                               action='store_true')
    delete_parser.add_argument('-synctimeout','-syncto',
                               help='sync timeout in seconds ',
                               dest='synctimeout',
                               default=0,
                               type=int)
    delete_parser.add_argument(
        '-forceDelete', '-fd',
        metavar='<forceDelete>',
        dest='forceDelete',
        help='Delete fileshare forecibly, default false',
        default=False)
    delete_parser.add_argument(
        '-deleteType', '-dt',
        metavar='<delete_type>',
        dest='delete_type',
        help='Delete fileshare either from Inventory only or full delete, default FULL',
        default='FULL',
        choices=["FULL", "VIPR_ONLY"])
    delete_parser.set_defaults(func=fileshare_delete)


def fileshare_delete(args):
    if not args.sync and args.synctimeout !=0:
        raise SOSError(SOSError.CMD_LINE_ERR,"error: Cannot use synctimeout without Sync ")
    if(not args.tenant):
        args.tenant = ""
    obj = Fileshare(args.ip, args.port)
    try:
        obj.delete(
            args.tenant + "/" + args.project + "/" + args.name,
            args.forceDelete, args.delete_type, args.sync,args.synctimeout)
    except SOSError as e:
        common.format_err_msg_and_raise("delete", "filesystem",
                                        e.err_text, e.err_code)
    # cifs acl update parser
def cifs_acl_parser(subcommand_parsers, common_parser):
    cifs_acl_parser = subcommand_parsers.add_parser(
        'share-acl',
        description='ViPR Filesystem Export rule CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Add/Update/Delete ACLs rules for file Share ')
    mandatory_args = cifs_acl_parser.add_argument_group(
        'mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                dest='name',
                                metavar='<filesystemname>',
                                help='Name of Filesystem',
                                required=True)
    mandatory_args.add_argument('-share', '-sh',
                               help='Name of SMB share',
                               dest='share',
                               required=True)
    mandatory_args.add_argument('-operation', '-op',
                                choices=["add", "update", "delete"],
                                dest='operation',
                                metavar='<acloperation>',
                                help='cifs acl operation',
                                required=True)
    cifs_acl_parser.add_argument('-permission', '-perm',
                                    dest='permission',
                                    choices=["FullControl", "Change", "Read"],
                                    metavar='<permission>',
                                    help='Provide permission for Acl')
    cifs_acl_parser.add_argument('-tenant', '-tn',
                                     metavar='<tenantname>',
                                     dest='tenant',
                                     help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    cifs_acl_parser.add_argument('-user', '-u',
                                    dest='user',
                                    metavar='<user>',
                                    help='User')
    cifs_acl_parser.add_argument('-domain','-dom',
                                    dest='domain',
                                    metavar='<domain>',
                                    help='Domain')
    cifs_acl_parser.add_argument('-group', '-grp',
                                    dest='group',
                                    metavar='<group>',
                                    help='Group')                    
    
    cifs_acl_parser.set_defaults(func=fileshare_acl)




def fileshare_acl(args):
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        if(not args.user and not args.permission):
            raise SOSError(SOSError.CMD_LINE_ERR, "Anonymous user should be provided to add/update/delete acl rule")
        if(args.user and args.group):
            raise SOSError(SOSError.CMD_LINE_ERR, "User and Group cannot be specified together")    
        
        
        res = obj.cifs_acl(args.tenant, args.project, 
                           args.name, 
                           args.share, 
                           args.operation, 
                           args.user, 
                           args.permission,
                           args.domain,
                           args.group)


    except SOSError as e:
                
        common.format_err_msg_and_raise("share-acl", "filesystem",
                                        e.err_text, e.err_code)



#NFSv4 ACL parser

def nfs_acl_parser(subcommand_parsers, common_parser):
    nfs_acl_parser = subcommand_parsers.add_parser(
        'nfs-acl',
        description='ViPR Filesystem NFSv4 ACL CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Add/Update/Delete ACLs rules for FileSystem ')
    mandatory_args = nfs_acl_parser.add_argument_group(
        'mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                dest='name',
                                metavar='<filesystemname>',
                                help='Name of Filesystem',
                                required=True)
    mandatory_args.add_argument('-operation', '-op',
                                choices=["add", "update", "delete"],
                                dest='operation',
                                metavar='<acloperation>',
                                help='nfs acl operation',
                                required=True)
    mandatory_args.add_argument('-permissions', '-perms',
                                    dest='permissions',
                                    choices=["Read", "Write", "Execute","Read,write" ,"Read,Execute","Write,Execute","Read,Write,Execute","FullControl"],
                                    metavar='<permissions>',
                                    help='Provide permissions for Acl',
                                    required=True)
    mandatory_args.add_argument('-permissiontype', '-permtype',
                                    dest='permissiontype',
                                    choices=["allow", "deny"],
                                    metavar='<permission_type>',
                                    help='Provide permission type for Acl',
                                    required=True)
    nfs_acl_parser.add_argument('-tenant', '-tn',
                                     metavar='<tenantname>',
                                     dest='tenant',
                                     help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    mandatory_args.add_argument('-user', '-u',
                                    dest='user',
                                    metavar='<user>',
                                    help='User',
                                    required=True)
    nfs_acl_parser.add_argument('-domain','-dom',
                                    dest='domain',
                                    metavar='<domain>',
                                    help='Domain')
    
    nfs_acl_parser.add_argument('-type','-t',
                                    dest='type',
                                    metavar='<type>',
                                    choices = ["user","group"],
                                    help='Type')
    nfs_acl_parser.add_argument('-subdirectory', '-subdir',
                                    dest='subdir',
                                    metavar='<subdirectory>',
                                    help='Subdirectory Name ')  
                  
    nfs_acl_parser.set_defaults(func=nfs_acl)


def nfs_acl(args):
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        
        res = obj.nfs_acl(args.tenant, args.project, 
                           args.name, 
                           args.subdir,
                           args.permissiontype, 
                           args.type,
                           args.operation, 
                           args.user, 
                           args.permissions,
                           args.domain)
    except SOSError as e:
        common.format_err_msg_and_raise("nfs-acl", "filesystem",
                                        e.err_text, e.err_code)

# Fileshare Delete routines

def acl_delete_parser(subcommand_parsers, common_parser):
    acl_delete_parser = subcommand_parsers.add_parser(
        'delete-acl',
        description='ViPR ACL Delete CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Delete a ACL of share')
    mandatory_args = acl_delete_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                metavar='<filesystemname>',
                                dest='name',
                                help='Name of Filesystem',
                                required=True)
    acl_delete_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    mandatory_args.add_argument('-share', '-sh',
                               help='Name of SMB share',
                               dest='share',
                               required=True)
    
    acl_delete_parser.set_defaults(func=fileshare_acl_delete)


def fileshare_acl_delete(args):
    if(not args.tenant):
        args.tenant = ""
    obj = Fileshare(args.ip, args.port)
    try:
        obj.delete_acl(
            args.tenant + "/" + args.project + "/" + args.name,
            args.share)
    except SOSError as e:
        common.format_err_msg_and_raise("delete-acl", "filesystem",
                                        e.err_text, e.err_code)
 

def nfs_acl_delete_parser(subcommand_parsers, common_parser):
    nfs_acl_delete_parser = subcommand_parsers.add_parser(
        'nfs-delete-acl',
        description='ViPR ACL Delete CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Deletes all the ACLs which are set on a particular filesystem as well as on its sub-directories')
    mandatory_args = nfs_acl_delete_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                metavar='<filesystemname>',
                                dest='name',
                                help='Name of Filesystem',
                                required=True)
    nfs_acl_delete_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')
    nfs_acl_delete_parser.add_argument('-subdirectory', '-subdir',
                                    dest='subdir',
                                    metavar='<subdirectory>',
                                    help='Subdirectory Name ') 
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    nfs_acl_delete_parser.set_defaults(func=nfs_acl_delete)


def nfs_acl_delete(args):
    if(not args.tenant):
        args.tenant = ""
    obj = Fileshare(args.ip, args.port)
    try:
        res = obj.nfs_acl_delete(
            args.tenant + "/" + args.project + "/" + args.name, args.subdir)
    except SOSError as e:
        common.format_err_msg_and_raise("delete-nfs-acl", "filesystem",
                                        e.err_text, e.err_code)
       
        
# routine to list the acls of a share .

def acl_list_parser(subcommand_parsers, common_parser):
    acl_list_parser = subcommand_parsers.add_parser(
        'list-acl',
        description='ViPR ACL List CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='LIST ACL of share')
    mandatory_args = acl_list_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                metavar='<filesystemname>',
                                dest='name',
                                help='Name of Filesystem',
                                required=True)
    acl_list_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    mandatory_args.add_argument('-share', '-sh',
                               help='Name of SMB share',
                               dest='share',
                               required=True)
    
 
    acl_list_parser.set_defaults(func=fileshare_acl_list)


def fileshare_acl_list(args):
    if(not args.tenant):
        args.tenant = ""
    obj = Fileshare(args.ip, args.port)
    try:
        res = obj.list_acl(
            args.tenant + "/" + args.project + "/" + args.name,
            args.share)
        if ( res == {}):
            print " No ACLs for the share"
        else:
            from common import TableGenerator
            TableGenerator(res['acl'], ['errorType','filesystem_id','permission','share_name','user']).printTable() 
        
    except SOSError as e:
        common.format_err_msg_and_raise("list-acl", "filesystem",
                                        e.err_text, e.err_code)
        
        


#NFS ACL LIST PARSER

def nfs_acl_list_parser(subcommand_parsers, common_parser):
    nfs_acl_list_parser = subcommand_parsers.add_parser(
        'nfs-list-acl',
        description='ViPR NFS ACL List CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='LIST ACL of Filesystem')
    mandatory_args = nfs_acl_list_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                metavar='<filesystemname>',
                                dest='name',
                                help='Name of Filesystem',
                                required=True)
    nfs_acl_list_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    nfs_acl_list_parser.add_argument('-subdirectory', '-subdir',
                                    dest='subdir',
                                    metavar='<subdirectory>',
                                    help='Subdirectory Name ')  
    nfs_acl_list_parser.add_argument('-alldirectories', '-alldir',
                                    dest='alldir',
                                    action = 'store_true',
                                    help='List All Directories')        
    
    nfs_acl_list_parser.set_defaults(func=nfs_acl_list)


def nfs_acl_list(args):
    if(not args.tenant):
        args.tenant = ""
    obj = Fileshare(args.ip, args.port)
    resultList = []
    try:
        res = obj.nfs_acl_list(
            args.tenant + "/" + args.project + "/" + args.name ,args.alldir, args.subdir)
        
        if ( len(res) == 0 ):
            print " No NFSv4 ACLs for the Filesystem/Subdirectory"
        else:
            from common import TableGenerator
            TableGenerator(res['nfs_acl'], ['domain','user','permissions','permission_type','type']).printTable() 
        
    except SOSError as e:
        common.format_err_msg_and_raise("list-nfs-acl", "filesystem",
                                        e.err_text, e.err_code)
       
# Fileshare Export routines


def export_parser(subcommand_parsers, common_parser):
    export_parser = subcommand_parsers.add_parser(
        'export',
        description='ViPR Filesystem Export CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Export a filesystem')
    mandatory_args = export_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                dest='name',
                                metavar='<filesystemname>',
                                help='Name of Filesystem',
                                required=True)
    export_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    export_parser.add_argument('-security', '-sec',
                               metavar='<security>',
                               dest='security',
                               help='Comma separated security type(s)')
    export_parser.add_argument('-permission', '-pe',
                               metavar='<permission>',
                               dest='permission',
                               help='Permission')
    export_parser.add_argument('-rootuser', '-ru',
                               metavar='<root_user>',
                               dest='root_user',
                               help='root user')
    export_parser.add_argument(
        '-endpoint', '-e',
        metavar='<endpoint>', dest='endpoint', nargs='+',
        help='Endpoints: host names, IP addresses, or netgroups')
    mandatory_args.add_argument('-protocol', '-pl',
                                help='Protocol',
                                choices=["NFS", "CIFS"],
                                dest='protocol',
                                required=True)
    export_parser.add_argument('-share', '-sh',
                               help='Name of SMB share',
                               dest='share')
    export_parser.add_argument('-description', '-desc',
                               help='Description of SMB share',
                               dest='desc')
    export_parser.add_argument('-permission_type', '-pt',
                               choices=['allow', 'deny'],
                               help='Type of permission of SMB share',
                               dest='permission_type')
    export_parser.add_argument('-subdir',
                               metavar="<sub directory>",
                               help='Export to FileSystem subdirectory',
                               dest='subdir')
    export_parser.add_argument('-synchronous', '-sync',
                               dest='sync',
                               help='Execute in synchronous mode',
                               action='store_true')
    export_parser.add_argument('-synctimeout','-syncto',
                               help='sync timeout in seconds ',
                               dest='synctimeout',
                               default=0,
                               type=int)
    export_parser.set_defaults(func=fileshare_export)


def fileshare_export(args):
    if not args.sync and args.synctimeout !=0:
        raise SOSError(SOSError.CMD_LINE_ERR,"error: Cannot use synctimeout without Sync ")

    try:
        if(args.protocol == "CIFS"):
            if(args.share is None):
                raise SOSError(SOSError.CMD_LINE_ERR,
                               'error: -share is required for CIFS export')
            if(args.desc is None):
                raise SOSError(
                    SOSError.CMD_LINE_ERR,
                    'error: -description is required for CIFS export')
        else:

            if(args.security is None):
                raise SOSError(
                    SOSError.CMD_LINE_ERR,
                    'error: -security is required for ' +
                    args.protocol + ' export')
            if(args.permission is None):
                raise SOSError(
                    SOSError.CMD_LINE_ERR,
                    'error: -permission is required for ' +
                    args.protocol + ' export')
            if(args.root_user is None):
                raise SOSError(
                    SOSError.CMD_LINE_ERR,
                    'error: -rootuser is required for ' +
                    args.protocol + ' export')
            if(args.endpoint is None):
                raise SOSError(
                    SOSError.CMD_LINE_ERR,
                    'error: -endpoint is required for ' +
                    args.protocol + ' export')

        if(not args.tenant):
            args.tenant = ""
        obj = Fileshare(args.ip, args.port)
        res = obj.export(
            args.tenant + "/" + args.project + "/" + args.name,
            args.security, args.permission, args.root_user, args.endpoint,
            args.protocol, args.share, args.desc,
            args.permission_type, args.subdir, args.sync,args.synctimeout)

#        if(args.sync == False):
#            return common.format_json_object(res)

    except SOSError as e:
        if (e.err_code == SOSError.NOT_FOUND_ERR):
            raise SOSError(e.err_code, "Export failed: " + e.err_text)
        else:
            raise e


# Fileshare UnExport routines

def unexport_parser(subcommand_parsers, common_parser):
    unexport_parser = subcommand_parsers.add_parser(
        'unexport',
        description='ViPR Filesystem Unexport CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Unexport a filesystem')
    mandatory_args = unexport_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                dest='name',
                                metavar='<filesystemname>',
                                help='Name of Filesystem',
                                required=True)
    unexport_parser.add_argument('-tenant', '-tn',
                                 metavar='<tenantname>',
                                 dest='tenant',
                                 help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    mandatory_args.add_argument('-protocol', '-pl',
                                help='Protocol',
                                choices=["NFS", "NFSv4", "CIFS"],
                                dest='protocol',
                                required=True)
    unexport_parser.add_argument('-share', '-sh',
                                 help='Name of SMB share',
                                 dest='share')
    unexport_parser.add_argument('-subdir',
                                 metavar="<sub directory>",
                                 help='Unexport from FileSystem sub-directory',
                                 dest='subdir')
    unexport_parser.add_argument('-alldir',
                                 action="store_true",
                                 help='Unexport FileSystem from all directories including sub-directories',
                                 dest='alldir')
    unexport_parser.add_argument('-synchronous', '-sync',
                                 dest='sync',
                                 help='Execute in synchronous mode',
                                 action='store_true')
    unexport_parser.add_argument('-synctimeout','-syncto',
                               help='sync timeout in seconds ',
                               dest='synctimeout',
                               default=0,
                               type=int)
    unexport_parser.set_defaults(func=fileshare_unexport)


def fileshare_unexport(args):
    if not args.sync and args.synctimeout !=0:
        raise SOSError(SOSError.CMD_LINE_ERR,"error: Cannot use synctimeout without Sync ")
    try:

        if(args.protocol == "CIFS"):
            if(args.share is None):
                raise SOSError(SOSError.CMD_LINE_ERR,
                               'error: -share is required for CIFS unexport')
        
        obj = Fileshare(args.ip, args.port)
        if(not args.tenant):
            args.tenant = ""
        res = obj.unexport(args.tenant + "/" + args.project + "/" + args.name, args.protocol, args.share, args.subdir, args.alldir, args.sync,args.synctimeout)
#        if(args.sync == False):
#            return common.format_json_object(res)

    except SOSError as e:
        if (e.err_code == SOSError.NOT_FOUND_ERR):
            raise SOSError(e.err_code, "Unexport failed: " + e.err_text)
        else:
            raise e


# fileshare ingest routines


def unmanaged_parser(subcommand_parsers, common_parser):
    unmanaged_parser = subcommand_parsers.add_parser(
        'unmanaged',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Unmanaged filesystem operations')
    subcommand_parsers = unmanaged_parser.add_subparsers(
        help='Use one of the commands')

    # ingest unmanaged volume
    ingest_parser = subcommand_parsers.add_parser(
        'ingest',
        parents=[common_parser],
        conflict_handler='resolve',
        help='ingest unmanaged filesystems into ViPR')
    mandatory_args = ingest_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-vpool', '-vp',
                                metavar='<vpool>',
                                dest='vpool',
                                help='Name of vpool',
                                required=True)
    ingest_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    mandatory_args.add_argument('-varray', '-va',
                                metavar='<varray>',
                                dest='varray',
                                help='Name of varray',
                                required=True)
    mandatory_args.add_argument('-filesystems', '-fs',
                                metavar='<filesystems>',
                                dest='filesystems',
                                help='Name or id of filesystem',
                                nargs='+',
                                required=True)

    # show unmanaged filesystem
    umshow_parser = subcommand_parsers.add_parser('show',
                                                  parents=[common_parser],
                                                  conflict_handler='resolve',
                                                  help='Show unmanaged filesystem')
    mandatory_args = umshow_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-filesystem', '-fs',
                                metavar='<filesystem>',
                                dest='filesystem',
                                help='Name or id of filesystem',
                                required=True)

    ingest_parser.set_defaults(func=unmanaged_filesystem_ingest)

    umshow_parser.set_defaults(func=unmanaged_filesystem_show)


def unmanaged_filesystem_ingest(args):
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        res = obj.unmanaged_filesystem_ingest(
            args.tenant, args.project,
            args.varray, args.vpool, args.filesystems)
    except SOSError as e:
        raise e


def unmanaged_filesystem_show(args):
    obj = Fileshare(args.ip, args.port)
    try:
        res = obj.unmanaged_filesystem_show(args.filesystem)
        return common.format_json_object(res)
    except SOSError as e:
        raise e

# Fileshare Show routines


def show_parser(subcommand_parsers, common_parser):
    show_parser = subcommand_parsers.add_parser(
        'show',
        description='ViPR Filesystem Show CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Show details of filesystem')
    mandatory_args = show_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                dest='name',
                                metavar='<filesystemname>',
                                help='Name of Filesystem',
                                required=True)
    show_parser.add_argument('-tenant', '-tn',
                             metavar='<tenantname>',
                             dest='tenant',
                             help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    show_parser.add_argument('-xml',
                             dest='xml',
                             action="store_true",
                             help='Display in XML format')
    show_parser.set_defaults(func=fileshare_show)


def fileshare_show(args):
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        res = obj.show(args.tenant + "/" + args.project + "/" + args.name,
                       False, args.xml)
        if(args.xml):
            return common.format_xml(res)
        return common.format_json_object(res)
    except SOSError as e:
        raise e


def show_exports_parser(subcommand_parsers, common_parser):
    show_exports_parser = subcommand_parsers.add_parser(
        'show-exports',
        description='ViPR Filesystem Show exports CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Show export details of filesystem')
    mandatory_args = show_exports_parser.add_argument_group(
        'mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                dest='name',
                                metavar='<filesystemname>',
                                help='Name of Filesystem',
                                required=True)
    show_exports_parser.add_argument('-tenant', '-tn',
                                     metavar='<tenantname>',
                                     dest='tenant',
                                     help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    show_exports_parser.add_argument('-subdir', 
                                     metavar='<subDirectory>',
                                     dest='subdir',
                                     help='Name of the Sub directory')
    show_exports_parser.add_argument('-alldir',
                                     dest='alldir',
                                     action='store_true',
                                     help='Show File System export information for All Directories')
    
    show_exports_parser.set_defaults(func=fileshare_exports_show)




def fileshare_exports_show(args):
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        res = obj.get_exports(
            args.tenant + "/" + args.project + "/" + args.name, args.subdir, args.alldir)
        if(res):
            return common.format_json_object(res)
    except SOSError as e:
        raise e



def export_rule_parser(subcommand_parsers, common_parser):
    export_rule_parser = subcommand_parsers.add_parser(
        'export-rule',
        description='ViPR Filesystem Export rule CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Add/Update/Delete Export rules for File Systems')
    mandatory_args = export_rule_parser.add_argument_group(
        'mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                dest='name',
                                metavar='<filesystemname>',
                                help='Name of Filesystem',
                                required=True)
    mandatory_args.add_argument('-operation', '-op',
                                choices=["add", "update", "delete"],
                                dest='operation',
                                metavar='<exportruleoperation>',
                                help='Export rule operation',
                                required=True)
    export_rule_parser.add_argument('-roothosts', '-rhosts',
                                    dest='roothosts',
                                    nargs = '+',
                                    metavar='<roothosts>',
                                    help='Root host names')
    export_rule_parser.add_argument('-readonlyhosts', '-rohosts',
                                    dest='readonlyhosts',
                                    nargs = '+',
                                    metavar='<readonlyhosts>',
                                    help='Read only host names')
    export_rule_parser.add_argument('-readwritehosts', '-rwhosts',
                                    dest='readwritehosts',
                                    nargs = '+',
                                    metavar='<readwritehosts>',
                                    help='Read write host names')

    export_rule_parser.add_argument('-tenant', '-tn',
                                     metavar='<tenantname>',
                                     dest='tenant',
                                     help='Name of tenant')
    mandatory_args.add_argument('-securityflavor', '-sec',
                                metavar='<securityflavor>',
                                dest='securityflavor',
                                help='Comma separated Security flavor(s)',
                                required=True)  
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    export_rule_parser.add_argument('-subdir', 
                                     metavar='<subDirectory>',
                                     dest='subdir',
                                     help='Name of the Sub Directory')
    export_rule_parser.add_argument('-user', '-u',
                                    dest='user',
                                    metavar='<user>',
                                    help='User')
    
    export_rule_parser.set_defaults(func=fileshare_export_rule)




def fileshare_export_rule(args):
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        if(not args.roothosts and not args.readonlyhosts and not args.readwritehosts):
            raise SOSError(SOSError.CMD_LINE_ERR, "At least one of the arguments : roothosts or readonlyhosts or readwritehosts should be provided to add/Update/delete the export rule")
        if(not args.user):
            raise SOSError(SOSError.CMD_LINE_ERR, "Anonymous user should be provided to add/update/delete export rule")
        res = obj.export_rule(
            args.tenant + "/" + args.project + "/" + args.name, args.operation, args.securityflavor, args.user, args.roothosts, args.readonlyhosts, args.readwritehosts, args.subdir)

    except SOSError as e:
        raise e



def show_shares_parser(subcommand_parsers, common_parser):
    show_exports_parser = subcommand_parsers.add_parser(
        'show-shares',
        description='ViPR Filesystem Show Shares CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Show shares of filesystem')
    mandatory_args = show_exports_parser.add_argument_group(
        'mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                dest='name',
                                metavar='<filesystemname>',
                                help='Name of Filesystem',
                                required=True)
    show_exports_parser.add_argument('-tenant', '-tn',
                                     metavar='<tenantname>',
                                     dest='tenant',
                                     help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    show_exports_parser.set_defaults(func=fileshare_shares_show)


def fileshare_shares_show(args):
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        res = obj.get_shares(
            args.tenant + "/" + args.project + "/" + args.name)
        if(res):
            return common.format_json_object(res)
    except SOSError as e:
        raise e


# Fileshare List routines

def list_parser(subcommand_parsers, common_parser):
    list_parser = subcommand_parsers.add_parser(
        'list',
        description='ViPR Filesystem List CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='List filesystems')
    mandatory_args = list_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of Project',
                                required=True)
    list_parser.add_argument('-tenant', '-tn',
                             metavar='<tenantname>',
                             dest='tenant',
                             help='Name of tenant')
    list_parser.add_argument('-verbose', '-v',
                             dest='verbose',
                             help='List filesystems with details',
                             action='store_true')
    list_parser.add_argument('-long', '-l',
                             dest='long',
                             help='List filesystems having more headers',
                             action='store_true')
    list_parser.set_defaults(func=fileshare_list)


def fileshare_list(args):
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        result = obj.list_fileshares(args.tenant + "/" + args.project)
        if(len(result) > 0):
            if(not args.verbose):
                for record in result:
                    if("fs_exports" in record):
                        del record["fs_exports"]
                    if("project" in record and "name" in record["project"]):
                        del record["project"]["name"]
                    if("vpool" in record and "vpool_params" in record["vpool"]
                       and record["vpool"]["vpool_params"]):
                        for vpool_param in record["vpool"]["vpool_params"]:
                            record[vpool_param["name"]] = vpool_param["value"]
                        record["vpool"] = None

                # show a short table
                from common import TableGenerator
                if(not args.long):
                    TableGenerator(result, ['name', 'capacity_gb',
                                            'protocols']).printTable()
                else:
                    TableGenerator(
                        result,
                        ['name', 'capacity_gb', 'protocols',
                         'thinly_provisioned', 'tags']).printTable()
            # show all items in json format
            else:
                return common.format_json_object(result)

        else:
            return
    except SOSError as e:
        raise e

def task_parser(subcommand_parsers, common_parser):
    task_parser = subcommand_parsers.add_parser(
        'tasks',
        description='ViPR Filesystem List tasks CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Show details of filesystem tasks')
    mandatory_args = task_parser.add_argument_group('mandatory arguments')

    task_parser.add_argument('-tenant', '-tn',
                             metavar='<tenantname>',
                             dest='tenant',
                             help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    task_parser.add_argument('-name', '-n',
                             dest='name',
                             metavar='<filesystemname>',
                             help='Name of filesystem')
    task_parser.add_argument('-id',
                             dest='id',
                             metavar='<id>',
                             help='Task ID')
    task_parser.add_argument('-v', '-verbose',
                             dest='verbose',
                             action="store_true",
                             help='List all tasks')
    task_parser.set_defaults(func=fileshare_list_tasks)


def fileshare_list_tasks(args):
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        if(args.id):
            res = obj.list_tasks(
                args.tenant + "/" + args.project,
                args.name, args.id)
            if(res):
                return common.format_json_object(res)
        elif(args.name):
            res = obj.list_tasks(args.tenant + "/" + args.project, args.name)
            if(res and len(res) > 0):
                if(args.verbose):
                    return common.format_json_object(res)
                else:
                    from common import TableGenerator
                    TableGenerator(res,
                                   ["module/id", "name", "state"]).printTable()
        else:
            res = obj.list_tasks(args.tenant + "/" + args.project)
            if(res and len(res) > 0):
                if(not args.verbose):
                    from common import TableGenerator
                    TableGenerator(res,
                                   ["module/id", "name", "state"]).printTable()
                else:
                    return common.format_json_object(res)

    except SOSError as e:
        raise e


def expand_parser(subcommand_parsers, common_parser):
    expand_parser = subcommand_parsers.add_parser(
        'expand',
        description='ViPR Filesystem Show CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Expand the filesystem')
    mandatory_args = expand_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                dest='name',
                                metavar='<filesystemname>',
                                help='Name of Filesystem',
                                required=True)
    expand_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    mandatory_args.add_argument(
        '-size', '-s',
        help='New size of filesystem: {number}[unit]. ' +
        'A size suffix of K for kilobytes, M for megabytes, ' +
        'G for gigabytes, T for terabytes is optional.' +
        'Default unit is bytes.',
        metavar='<filesystemsize[kKmMgGtT]>',
        dest='size', required=True)
    expand_parser.add_argument('-synchronous', '-sync',
                               dest='sync',
                               help='Execute in synchronous mode',
                               action='store_true')
    
    expand_parser.add_argument('-synctimeout','-syncto',
                               help='sync timeout in seconds ',
                               dest='synctimeout',
                               default=0,
                               type=int)
    expand_parser.set_defaults(func=fileshare_expand)


def fileshare_expand(args):
    if not args.sync and args.synctimeout !=0:
        raise SOSError(SOSError.CMD_LINE_ERR,"error: Cannot use synctimeout without Sync ")
    size = common.to_bytes(args.size)
    if(not size):
        raise SOSError(SOSError.CMD_LINE_ERR, 'error: Invalid input for -size')

    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""

        res = obj.expand(args.tenant + "/" + args.project +
                         "/" + args.name, size, args.sync,args.synctimeout)
    except SOSError as e:
        raise e


def tag_parser(subcommand_parsers, common_parser):
    tag_parser = subcommand_parsers.add_parser(
        'tag',
        description='ViPR Fileshare Tag CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Tag a filesystem')
    mandatory_args = tag_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                help='Name of filesystem',
                                metavar='<filesystemname>',
                                dest='name',
                                required=True)
    tag_parser.add_argument('-tenant', '-tn',
                            metavar='<tenantname>',
                            dest='tenant',
                            help='Name of tenant')

    tag.add_mandatory_project_parameter(mandatory_args)

    tag.add_tag_parameters(tag_parser)

    tag_parser.set_defaults(func=fileshare_tag)


def fileshare_tag(args):
    if(not args.tenant):
        args.tenant = ""

    try:
        if(args.add is None and args.remove is None):
            raise SOSError(
                SOSError.CMD_LINE_ERR,
                "viprcli fileshare tag: error: at least one of " +
                "the arguments -add -remove is required")

        obj = Fileshare(args.ip, args.port)
        res = obj.tag(args.tenant + "/" + args.project + "/" + args.name,
                      args.add,
                      args.remove)
    except SOSError as e:
        common.format_err_msg_and_raise("fileshare", "tag",
                                        e.err_text, e.err_code)
        
def assign_policy_parser(subcommand_parsers, common_parser):
    assign_policy_parser = subcommand_parsers.add_parser(
        'assign-policy',
        description='ViPR Fileshare Policy assign CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Assign a snapshot scheduling policy to a filesystem')
    mandatory_args = assign_policy_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                help='Name of filesystem',
                                metavar='<filesystemname>',
                                dest='name',
                                required=True)
    mandatory_args.add_argument('-policyname', '-polnm',
                               metavar='<policyname>',
                               dest='polname',
                               help='Name of policy',
                               required=True)
    mandatory_args.add_argument('-tenant', '-tn',
                            metavar='<tenantname>',
                            dest='tenant',
                            help='Name of tenant',
                            required=True)
    mandatory_args.add_argument('-project', '-pr',
                            metavar='<projectname>',
                            dest='project',
                            help='Name of Project',
                            required=True)
    assign_policy_parser.add_argument('-targetvarrays', '-trgvarrays',
                            metavar='<targetvarrays>',
                            dest='targetvarrays',
                            help='target varrays for file replication')
    assign_policy_parser.set_defaults(func=assign_policy)


def assign_policy(args):
    try:
        
        policy = FilePolicy(args.ip,
                        args.port).filepolicy_query(args.polname)
        policyid = policy['id']
        obj = Fileshare(args.ip, args.port)
        
        res = obj.assign_policy(args.tenant + "/" + args.project + "/" + args.name,
                      args.polname,
                      args.tenant, policyid, args.targetvarrays)
        return
    except SOSError as e:
        common.format_err_msg_and_raise("fileshare", "assign",
                                        e.err_text, e.err_code)
        

def unassign_policy_parser(subcommand_parsers, common_parser):
    unassign_policy_parser = subcommand_parsers.add_parser(
        'unassign-policy',
        description='ViPR Fileshare Policy unassign CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Unassign a snapshot scheduling policy from a filesystem')
    mandatory_args = unassign_policy_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                help='Name of filesystem',
                                metavar='<filesystemname>',
                                dest='name',
                                required=True)
    mandatory_args.add_argument('-policyname', '-polnm',
                               metavar='<policyname>',
                               dest='polname',
                               help='Name of policy',
                               required=True)
    mandatory_args.add_argument('-tenant', '-tn',
                            metavar='<tenantname>',
                            dest='tenant',
                            help='Name of tenant',
                            required=True)
    mandatory_args.add_argument('-project', '-pr',
                            metavar='<projectname>',
                            dest='project',
                            help='Name of Project',
                            required=True)

    unassign_policy_parser.set_defaults(func=unassign_policy)


def unassign_policy(args):
    try:
        policy = FilePolicy(args.ip,
                        args.port).filepolicy_query(args.polname)
        policyid = policy['id']
        obj = Fileshare(args.ip, args.port)
        
        res = obj.unassign_policy(args.tenant + "/" + args.project + "/" + args.name,
                      args.polname,
                      args.tenant, policyid)
        return
    except SOSError as e:
        common.format_err_msg_and_raise("fileshare", "un-assign",
                                        e.err_text, e.err_code)
        

def policy_list_parser(subcommand_parsers, common_parser):
    policy_list_parser = subcommand_parsers.add_parser(
        'list-policy',
        description='ViPR Fileshare Policy list CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='List the snapshot scheduling policies of a filesystem')
    mandatory_args = policy_list_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                help='Name of filesystem',
                                metavar='<filesystemname>',
                                dest='name',
                                required=True)
    mandatory_args.add_argument('-tenant', '-tn',
                            metavar='<tenantname>',
                            dest='tenant',
                            help='Name of tenant',
                            required=True)
    mandatory_args.add_argument('-project', '-pr',
                            metavar='<projectname>',
                            dest='project',
                            help='Name of Project',
                            required=True)

    policy_list_parser.set_defaults(func=policy_list)


def policy_list(args):
    try:
        obj = Fileshare(args.ip, args.port)
        res = obj.policy_list(args.tenant + "/" + args.project + "/" + args.name)
        return common.format_json_object(res)
    except SOSError as e:
        common.format_err_msg_and_raise("fileshare", "assign",
                                        e.err_text, e.err_code)

        
def continous_copies_start_parser(subcommand_parsers, common_parser):
    # start continous copies command parser
    continous_copies_start_parser = subcommand_parsers.add_parser(
        'start-replication',
        description='ViPR fileshare replication start cli usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Start replication of the File System')
    mandatory_args = continous_copies_start_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                help='Name of filesystem',
                                metavar='<filesystemname>',
                                dest='name',
                                required=True)
    continous_copies_start_parser.add_argument('-tenant', '-tn',
                             metavar='<tenantname>',
                             dest='tenant',
                             help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    continous_copies_start_parser.add_argument('-synchronous', '-sync',
                               dest='sync',
                               help='Execute in synchronous mode',
                               action='store_true')
    continous_copies_start_parser.add_argument('-synctimeout','-syncto',
                               help='sync timeout in seconds ',
                               dest='synctimeout',
                               default=0,
                               type=int)
    
    continous_copies_start_parser.set_defaults(func=continous_copies_start)


def continous_copies_start(args):
    if not args.sync and args.synctimeout !=0:
        raise SOSError(SOSError.CMD_LINE_ERR,"error: Cannot use synctimeout without Sync ")
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        res = obj.continous_copies_start(args.tenant + "/" + args.project + "/" + args.name, args.sync, args.synctimeout)
        return
    except SOSError as e:
        raise e
    
def continous_copies_pause_parser(subcommand_parsers, common_parser):
    # pause continous copies command parser
    continous_copies_pause_parser = subcommand_parsers.add_parser(
        'pause-replication',
        description='ViPR fileshare replication pause cli usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Pause replication of the File System')
    mandatory_args = continous_copies_pause_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                help='Name of filesystem',
                                metavar='<filesystemname>',
                                dest='name',
                                required=True)
    continous_copies_pause_parser.add_argument('-tenant', '-tn',
                             metavar='<tenantname>',
                             dest='tenant',
                             help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    continous_copies_pause_parser.add_argument('-synchronous', '-sync',
                               dest='sync',
                               help='Execute in synchronous mode',
                               action='store_true')
    continous_copies_pause_parser.add_argument('-synctimeout','-syncto',
                               help='sync timeout in seconds ',
                               dest='synctimeout',
                               default=0,
                               type=int)
    continous_copies_pause_parser.set_defaults(func=continous_copies_pause)


def continous_copies_pause(args):
    if not args.sync and args.synctimeout !=0:
        raise SOSError(SOSError.CMD_LINE_ERR,"error: Cannot use synctimeout without Sync ")
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        res = obj.continous_copies_pause(args.tenant + "/" + args.project + "/" + args.name, args.sync, args.synctimeout)
        return
    except SOSError as e:
        raise e
    

def continous_copies_resume_parser(subcommand_parsers, common_parser):
    # resume continous copies command parser
    continous_copies_resume_parser = subcommand_parsers.add_parser(
        'resume-replication',
        description='ViPR fileshare replication resume cli usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Resume replication of the File System')
    mandatory_args = continous_copies_resume_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                help='Name of filesystem',
                                metavar='<filesystemname>',
                                dest='name',
                                required=True)
    continous_copies_resume_parser.add_argument('-tenant', '-tn',
                             metavar='<tenantname>',
                             dest='tenant',
                             help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    continous_copies_resume_parser.add_argument('-synchronous', '-sync',
                               dest='sync',
                               help='Execute in synchronous mode',
                               action='store_true')
    continous_copies_resume_parser.add_argument('-synctimeout','-syncto',
                               help='sync timeout in seconds ',
                               dest='synctimeout',
                               default=0,
                               type=int)
    continous_copies_resume_parser.set_defaults(func=continous_copies_resume)


def continous_copies_resume(args):
    if not args.sync and args.synctimeout !=0:
        raise SOSError(SOSError.CMD_LINE_ERR,"error: Cannot use synctimeout without Sync ")
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        res = obj.continous_copies_resume(args.tenant + "/" + args.project + "/" + args.name, args.sync, args.synctimeout)
        return
    except SOSError as e:
        raise e
    
def continous_copies_stop_parser(subcommand_parsers, common_parser):
    # stop continous copies command parser
    continous_copies_stop_parser = subcommand_parsers.add_parser(
        'stop-replication',
        description='ViPR fileshare replication stop cli usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Stop replication of the File System')
    mandatory_args = continous_copies_stop_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                help='Name of filesystem',
                                metavar='<filesystemname>',
                                dest='name',
                                required=True)
    continous_copies_stop_parser.add_argument('-tenant', '-tn',
                             metavar='<tenantname>',
                             dest='tenant',
                             help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    continous_copies_stop_parser.add_argument('-synchronous', '-sync',
                               dest='sync',
                               help='Execute in synchronous mode',
                               action='store_true')
    continous_copies_stop_parser.add_argument('-synctimeout','-syncto',
                               help='sync timeout in seconds ',
                               dest='synctimeout',
                               default=0,
                               type=int)
    continous_copies_stop_parser.set_defaults(func=continous_copies_stop)


def continous_copies_stop(args):
    if not args.sync and args.synctimeout !=0:
        raise SOSError(SOSError.CMD_LINE_ERR,"error: Cannot use synctimeout without Sync ")
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        res = obj.continous_copies_stop(args.tenant + "/" + args.project + "/" + args.name, args.sync, args.synctimeout)
        return
    except SOSError as e:
        raise e
    
    

def continous_copies_failover_parser(subcommand_parsers, common_parser):
    # failover continous copies command parser
    continous_copies_failover_parser = subcommand_parsers.add_parser(
        'failover-replication',
        description='ViPR fileshare replication failover cli usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Failover replication of the filesystem')
    mandatory_args = continous_copies_failover_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                help='Name of filesystem',
                                metavar='<filesystemname>',
                                dest='name',
                                required=True)
    continous_copies_failover_parser.add_argument('-tenant', '-tn',
                             metavar='<tenantname>',
                             dest='tenant',
                             help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    continous_copies_failover_parser.add_argument('-synchronous', '-sync',
                               dest='sync',
                               help='Execute in synchronous mode',
                               action='store_true')
    continous_copies_failover_parser.add_argument('-replicateconfig', '-rc',
                               help=' whether to replicate NFS exports and CIFS Shares from source to target',
                               choices=Fileshare.BOOL_TYPE_LIST,
                               dest='replicateconfig')
    continous_copies_failover_parser.add_argument('-synctimeout','-syncto',
                               help='sync timeout in seconds ',
                               dest='synctimeout',
                               default=0,
                               type=int)
    continous_copies_failover_parser.set_defaults(func=continous_copies_failover)


def continous_copies_failover(args):
    if not args.sync and args.synctimeout !=0:
        raise SOSError(SOSError.CMD_LINE_ERR,"error: Cannot use synctimeout without Sync ")
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        res = obj.continous_copies_failover(args.tenant + "/" + args.project + "/" + args.name,args.replicateconfig, args.sync, args.synctimeout  )
        return
    except SOSError as e:
        raise e
    
def continous_copies_failback_parser(subcommand_parsers, common_parser):
    # failback continous copies command parser
    continous_copies_failback_parser = subcommand_parsers.add_parser(
        'failback-replication',
        description='ViPR fileshare replication failback cli usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Failback replication of the filesystem')
    mandatory_args = continous_copies_failback_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                help='Name of filesystem',
                                metavar='<filesystemname>',
                                dest='name',
                                required=True)
    continous_copies_failback_parser.add_argument('-tenant', '-tn',
                             metavar='<tenantname>',
                             dest='tenant',
                             help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    continous_copies_failback_parser.add_argument('-synchronous', '-sync',
                               dest='sync',
                               help='Execute in synchronous mode',
                               action='store_true')
    continous_copies_failback_parser.add_argument('-synctimeout','-syncto',
                               help='sync timeout in seconds ',
                               dest='synctimeout',
                               default=0,
                               type=int)
    continous_copies_failback_parser.add_argument('-replicateconfig', '-rc',
                               help=' whether to replicate NFS exports and CIFS Shares from target to source',
                               choices=Fileshare.BOOL_TYPE_LIST,
                               dest='replicateconfig')						   
    continous_copies_failback_parser.set_defaults(func=continous_copies_failback)


def continous_copies_failback(args):
    if not args.sync and args.synctimeout !=0:
        raise SOSError(SOSError.CMD_LINE_ERR,"error: Cannot use synctimeout without Sync ")
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        res = obj.continous_copies_failback(args.tenant + "/" + args.project + "/" + args.name,args.replicateconfig, args.sync, args.synctimeout)
        return
    except SOSError as e:
        raise e
    
def continous_copies_create_parser(subcommand_parsers, common_parser):
    # create continous copies command parser
    continous_copies_create_parser = subcommand_parsers.add_parser(
        'create-replication-copy',
        description='ViPR fileshare create replication copy cli usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Create replication copy of the File System')
    mandatory_args = continous_copies_create_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                help='Name of filesystem',
                                metavar='<filesystemname>',
                                dest='name',
                                required=True)
    continous_copies_create_parser.add_argument('-tenant', '-tn',
                             metavar='<tenantname>',
                             dest='tenant',
                             help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    continous_copies_create_parser.add_argument('-targetname', '-tgn',
                             metavar='<targetname>',
                             dest='target',
                             help='Name of target')
    continous_copies_create_parser.add_argument('-synchronous', '-sync',
                               dest='sync',
                               help='Execute in synchronous mode',
                               action='store_true')
    continous_copies_create_parser.add_argument('-synctimeout','-syncto',
                               help='sync timeout in seconds ',
                               dest='synctimeout',
                               default=0,
                               type=int)
    continous_copies_create_parser.set_defaults(func=continous_copies_create)


def continous_copies_create(args):
    if not args.sync and args.synctimeout !=0:
        raise SOSError(SOSError.CMD_LINE_ERR,"error: Cannot use synctimeout without Sync ")
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        res = obj.continous_copies_create(args.tenant + "/" + args.project + "/" + args.name, args.sync, args.target, args.synctimeout)
        return
    except SOSError as e:
        raise e
    
    
def continous_copies_deactivate_parser(subcommand_parsers, common_parser):
    # deactivate continous copies command parser
    continous_copies_deactivate_parser = subcommand_parsers.add_parser(
        'remove-replication-copy',
        description='ViPR fileshare remove replication copy cli usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Remove replication copy of the File System')
    mandatory_args = continous_copies_deactivate_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                help='Name of filesystem',
                                metavar='<filesystemname>',
                                dest='name',
                                required=True)
    continous_copies_deactivate_parser.add_argument('-tenant', '-tn',
                             metavar='<tenantname>',
                             dest='tenant',
                             help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    continous_copies_deactivate_parser.add_argument('-synchronous', '-sync',
                               dest='sync',
                               help='Execute in synchronous mode',
                               action='store_true')
    continous_copies_deactivate_parser.add_argument('-synctimeout','-syncto',
                               help='sync timeout in seconds ',
                               dest='synctimeout',
                               default=0,
                               type=int)
    continous_copies_deactivate_parser.set_defaults(func=continous_copies_deactivate)


def continous_copies_deactivate(args):
    if not args.sync and args.synctimeout !=0:
        raise SOSError(SOSError.CMD_LINE_ERR,"error: Cannot use synctimeout without Sync ")
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        res = obj.continous_copies_deactivate(args.tenant + "/" + args.project + "/" + args.name, args.sync, args.synctimeout)
        return
    except SOSError as e:
        raise e
    

def continous_copies_refresh_parser(subcommand_parsers, common_parser):
    # refresh continous copies command parser
    continous_copies_refresh_parser = subcommand_parsers.add_parser(
        'refresh-replication-copy',
        description='ViPR fileshare replication copy refresh cli usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Refresh replication copy of the filesystem')
    mandatory_args = continous_copies_refresh_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                help='Name of filesystem',
                                metavar='<filesystemname>',
                                dest='name',
                                required=True)
    continous_copies_refresh_parser.add_argument('-tenant', '-tn',
                             metavar='<tenantname>',
                             dest='tenant',
                             help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    continous_copies_refresh_parser.add_argument('-synchronous', '-sync',
                               dest='sync',
                               help='Execute in synchronous mode',
                               action='store_true')
    continous_copies_refresh_parser.add_argument('-synctimeout','-syncto',
                               help='sync timeout in seconds ',
                               dest='synctimeout',
                               default=0,
                               type=int)
    continous_copies_refresh_parser.set_defaults(func=continous_copies_refresh)


def continous_copies_refresh(args):
    if not args.sync and args.synctimeout !=0:
        raise SOSError(SOSError.CMD_LINE_ERR,"error: Cannot use synctimeout without Sync ")
    obj = Fileshare(args.ip, args.port)
    try:
        if(not args.tenant):
            args.tenant = ""
        res = obj.continous_copies_refresh(args.tenant + "/" + args.project + "/" + args.name, args.sync, args.synctimeout)
        return
    except SOSError as e:
        raise e
    
    
def change_vpool_parser(subcommand_parsers, common_parser):
    # change vpool command parser
    change_vpool_parser = subcommand_parsers.add_parser(
        'change-vpool',
        description='ViPR fileshare change vpool cli usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Move File System from one virtual pool to another')
    mandatory_args = change_vpool_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                help='Name of filesystem',
                                metavar='<filesystemname>',
                                dest='name',
                                required=True)
    change_vpool_parser.add_argument('-tenant', '-tn',
                             metavar='<tenantname>',
                             dest='tenant',
                             help='Name of tenant')
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of project',
                                required=True)
    mandatory_args.add_argument('-vpool', '-vp',
                                help='Name of the target vpool',
                                metavar='<vpool>',
                                dest='vpool',
                                required=True)
    change_vpool_parser.set_defaults(func=change_vpool)


def change_vpool(args):
    obj = Fileshare(args.ip, args.port)
    from virtualpool import VirtualPool
    vpool_obj = VirtualPool(args.ip, args.port)
    vpoolid = vpool_obj.vpool_query(args.vpool, "file")
    try:
        if(not args.tenant):
            args.tenant = ""
        res = obj.change_vpool(args.tenant + "/" + args.project + "/" + args.name, vpoolid)
        return
    except SOSError as e:
        raise e
    

def schedule_snapshots_list_parser(subcommand_parsers, common_parser):
    schedule_snapshots_list_parser = subcommand_parsers.add_parser(
        'schedule-snapshots-list',
        description='ViPR Schedule snapshots list CLI usage.',
        parents=[common_parser],
        conflict_handler='resolve',
        help='List of schedule snapshots of a file system created by schedule policy')
    mandatory_args = schedule_snapshots_list_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument('-name', '-n',
                                help='Name of filesystem',
                                metavar='<filesystemname>',
                                dest='name',
                                required=True)
    mandatory_args.add_argument('-policyname', '-polnm',
                               metavar='<policyname>',
                               dest='polname',
                               help='Name of policy',
                               required=True)
    mandatory_args.add_argument('-tenant', '-tn',
                            metavar='<tenantname>',
                            dest='tenant',
                            help='Name of tenant',
                            required=True)
    mandatory_args.add_argument('-project', '-pr',
                            metavar='<projectname>',
                            dest='project',
                            help='Name of Project',
                            required=True)

    schedule_snapshots_list_parser.set_defaults(func=schedule_snapshots_list)


def schedule_snapshots_list(args):
    try:
        from schedulepolicy import Schedulepolicy
        policy = Schedulepolicy(args.ip,
                        args.port).get_policy_from_name(args.polname, args.tenant)
        policyid = policy['id']
        obj = Fileshare(args.ip, args.port)
        
        res = obj.schedule_snapshots_list(args.tenant + "/" + args.project + "/" + args.name,
                      args.polname,
                      args.tenant, policyid)
        return common.format_json_object(res)
    except SOSError as e:
        common.format_err_msg_and_raise("fileshare", "schedule snapshots",
                                        e.err_text, e.err_code)


def mount_parser(subcommand_parsers, common_parser):
    # create command parser
    mount_parser = subcommand_parsers.add_parser(
        'mount',
        description='ViPR mount export CLI usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Mounts an export for the given filesystem')

    mandatory_args = mount_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument(
        '-hst', '-hstname',
        help='name of the Host on which the export is supposed to be mounted',
        metavar='<hostname>',
        dest='hostname',
        required=True)
    mandatory_args.add_argument('-fs', '-filesystem',
                                help='Name of filesystem',
                                metavar='<filesystem>',
                                dest='filesystem',
                                required=True)
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of Project',
                                required=True)
    mount_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')    
    mandatory_args.add_argument(
        '-f', '-fstype',
        help='the mount fs type',
        metavar='<fstype>',
        dest='fstype',
        choices=Fileshare.MOUNT_FS_TYPES,
        required=True)
    mandatory_args.add_argument(
        '-mp', '-mountpath',
        help='the mount path',
        metavar='<mountpath>',
        dest='mountpath',
        required=True)
    mandatory_args.add_argument(
        '-sec', '-security',
        help='the security style for the mount',
        metavar='<security>',
        dest='security',
        choices=Fileshare.MOUNT_SEC_TYPES,
        required=True)
    mount_parser.add_argument(
        '-dir', '-subdirectory',
        help='the mount subdirectory',
        metavar='<subdirectory>',
        dest='subdirectory'
        )
    mount_parser.add_argument('-synchronous', '-sync',
                               dest='sync',
                               help='Synchronous mount create',
                               action='store_true')

    mount_parser.add_argument('-synctimeout', '-syncto',
                               help='sync timeout in seconds ',
                               dest='synctimeout',
                               default=0,
                               type=int)

    mount_parser.set_defaults(func=fileshare_mount)



'''
Preprocessor for the mount operation
'''

def fileshare_mount(args):
    if not args.sync and args.synctimeout != 0:
        raise SOSError(SOSError.CMD_LINE_ERR, "error: Cannot use synctimeout without Sync ")
    obj = Fileshare(args.ip, args.port)
  
    try:
        resourceUri = obj.storageResource_query(
            args.filesystem,
            args.project,
            args.tenant)
        from host import Host
        host_obj = Host(args.ip, args.port)
        try:
            host_uri = host_obj.query_by_name(args.hostname, args.tenant)
        except SOSError as e:
            raise e

        obj.mount(resourceUri, host_uri, args.subdirectory, args.security, args.mountpath, args.fstype, args.sync, args.synctimeout)

    except SOSError as e:
        if e.err_code == SOSError.SOS_FAILURE_ERR:
            raise SOSError(
                SOSError.SOS_FAILURE_ERR,
                "fileshare Mount: " + 
                args.filesystem + 
                ", mount Failed\n" + 
                e.err_text)
        else:
            common.format_err_msg_and_raise(
                "mount",
                "mountNFS",
                e.err_text,
                e.err_code)


# list command parser
def mountlist_parser(subcommand_parsers, common_parser):
    mountlist_parser = subcommand_parsers.add_parser(
        'mountlist',
        description='ViPR mount List CLI usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Lists mounts for the given filesystem')
    mandatory_args = mountlist_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument(
        '-fs', '-filesystem',
        help='Name of the Filesystem',
        metavar='<filesystem>',
        dest='filesystem',
        required=True)
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of Project',
                                required=True)
    mountlist_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')
    mountlist_parser.set_defaults(func=mountnfs_list)


'''
Preprocessor for mount list operation
'''

def mountnfs_list(args):
    obj = Fileshare(args.ip, args.port)    
    try:
        resourceUri = obj.storageResource_query(
            args.filesystem,
            args.project,
            args.tenant)

        mount_obj = obj.mount_list(resourceUri)
        if mount_obj:
            return common.format_json_object(mount_obj) 
        else:
            return

    except SOSError as e:
        common.format_err_msg_and_raise(
            "list",
            "mount",
            e.err_text,
            e.err_code)

# Start Parser definitions
def unmount_parser(subcommand_parsers, common_parser):
    # create command parser
    unmount_parser = subcommand_parsers.add_parser(
        'unmount',
        description='ViPR unmount export CLI usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Unmounts an export for the given filesystem')
    mandatory_args = unmount_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument(
        '-hst', '-hstname',
        help='name of the Host on which the export is mounted',
        metavar='<hostname>',
        dest='hostname',
        required=True)
    mandatory_args.add_argument('-fs', '-filesystem',
                                help='Name of filesystem',
                                metavar='<filesystem>',
                                dest='filesystem',
                                required=True)
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of Project',
                                required=True)
    unmount_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')
    mandatory_args.add_argument(
        '-mp', '-mountpath',
        help='the mount fs path',
        metavar='<mountpath>',
        dest='mountpath',
        required=True)
    unmount_parser.add_argument('-synchronous', '-sync',
                               dest='sync',
                               help='Synchronous mount create',
                               action='store_true')

    unmount_parser.add_argument('-synctimeout', '-syncto',
                               help='sync timeout in seconds ',
                               dest='synctimeout',
                               default=0,
                               type=int)

    unmount_parser.set_defaults(func=mountnfs_unmount)



'''
Preprocessor for the mount operation
'''

def mountnfs_unmount(args):
    if not args.sync and args.synctimeout != 0:
        raise SOSError(SOSError.CMD_LINE_ERR, "error: Cannot use synctimeout without Sync ")
    obj = Fileshare(args.ip, args.port)
  
    try:
        resourceUri = obj.storageResource_query(
            args.filesystem,
            args.project,
            args.tenant)
        from host import Host
        host_obj = Host(args.ip, args.port)
        try:
            host_uri = host_obj.query_by_name(args.hostname, args.tenant)
        except SOSError as e:
            raise e

        obj.unmount(resourceUri, host_uri, args.mountpath, args.sync, args.synctimeout)

    except SOSError as e:
        if e.err_code == SOSError.SOS_FAILURE_ERR:
            raise SOSError(
                SOSError.SOS_FAILURE_ERR,
                "MountNFS: " + 
                args.filesystem + 
                ", unmount Failed\n" + 
                e.err_text)
        else:
            common.format_err_msg_and_raise(
                "unmount",
                "mountNFS",
                e.err_text,
                e.err_code)

#
# Fileshare Main parser routine
#
def fileshare_parser(parent_subparser, common_parser):
    # main project parser

    parser = parent_subparser.add_parser(
        'filesystem',
        description='ViPR filesystem CLI usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Operations on filesystem')
    subcommand_parsers = parser.add_subparsers(help='Use one of subcommands')

    # create command parser
    create_parser(subcommand_parsers, common_parser)

    # update command parser
    update_parser(subcommand_parsers, common_parser)

    # delete command parser
    delete_parser(subcommand_parsers, common_parser)

    # show command parser
    show_parser(subcommand_parsers, common_parser)

    # show exports command parser
    show_exports_parser(subcommand_parsers, common_parser)

    # show shares command parser
    show_shares_parser(subcommand_parsers, common_parser)

    # export command parser
    export_parser(subcommand_parsers, common_parser)

    # unexport command parser
    unexport_parser(subcommand_parsers, common_parser)

    # list command parser
    list_parser(subcommand_parsers, common_parser)

    # expand fileshare parser
    expand_parser(subcommand_parsers, common_parser)

    # task list command parser
    task_parser(subcommand_parsers, common_parser)

    # unmanaged filesystem  command parser
    unmanaged_parser(subcommand_parsers, common_parser)

    # tag  filesystem  command parser
    tag_parser(subcommand_parsers, common_parser)

    # Export rule filesystem command parser
    export_rule_parser(subcommand_parsers, common_parser)
    
    # acl delete command parser
    acl_delete_parser(subcommand_parsers, common_parser)
    
    #ACL LIST Parser 
    
    acl_list_parser(subcommand_parsers, common_parser)

    #ACL fileshare command parser 
    cifs_acl_parser(subcommand_parsers, common_parser)
    
    #ACL FOR NFS FILESYSTEM
    nfs_acl_parser(subcommand_parsers, common_parser)
    
    #ACL LIST PARSER
    nfs_acl_list_parser(subcommand_parsers, common_parser)
    
    
    #ACL DELETE PARSER
    nfs_acl_delete_parser(subcommand_parsers, common_parser)
    
    #assign policy command parser
    assign_policy_parser(subcommand_parsers, common_parser)
    
    #unassign policy command parser
    unassign_policy_parser(subcommand_parsers, common_parser)
    
    #policy list command parser
    policy_list_parser(subcommand_parsers, common_parser)
    
    #CONTINOUS COPIES START PARSER
    continous_copies_start_parser(subcommand_parsers, common_parser)
    
    #CONTINOUS COPIES PAUSE PARSER
    continous_copies_pause_parser(subcommand_parsers, common_parser)
    
    #CONTINOUS COPIES RESUME PARSER
    continous_copies_resume_parser(subcommand_parsers, common_parser)
    
    #CONTINOUS COPIES STOP PARSER
    continous_copies_stop_parser(subcommand_parsers, common_parser)
    
    #CONTINOUS COPIES FAILOVER PARSER
    continous_copies_failover_parser(subcommand_parsers, common_parser)
    
    #CONTINOUS COPIES FAILBACK PARSER
    continous_copies_failback_parser(subcommand_parsers, common_parser)
    
    #CONTINOUS COPIES CREATE PARSER
    continous_copies_create_parser(subcommand_parsers, common_parser)
    
    #CONTINOUS COPIES DEACTIVATE PARSER
    continous_copies_deactivate_parser(subcommand_parsers, common_parser)
    
    #CONTINOUS COPIES REFRESH PARSER
    continous_copies_refresh_parser(subcommand_parsers, common_parser)
    
    #change vpool command parser
    change_vpool_parser(subcommand_parsers, common_parser)
    
    #schedule snapshots list parser
    schedule_snapshots_list_parser(subcommand_parsers, common_parser)
    
    # mount command parser
    mount_parser(subcommand_parsers, common_parser)

    # list command parser
    mountlist_parser(subcommand_parsers, common_parser)
    
    # unmount command parser
    unmount_parser(subcommand_parsers, common_parser)
