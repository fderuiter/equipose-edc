# OpenClinica Release Notes
**OpenClinica Version:** 3.18-SNAPSHOT
**Document Version:** 1.0  

**Updated:** 2026-07-23

## Overview

This is OpenClinica release 3.18-SNAPSHOT.  

The web application and the web services piece of OpenClinica are in separate war files. 

## Contents

I. Overall Product Features  
II. Issues Addressed and Known Issues  
III. Software Dependencies and System Requirements  
IV. About OpenClinica  
V. GNU LGPL License  

## I. Overall Product Features

The main functionality includes:
* **Submit Data:** Allows subject enrollment, data submission and validation for use by clinicians and research associates as well as Query Management and Bulk Data Import.
* **Monitor and Manage Data:** Enables ongoing data management and monitoring.
* **Extract Data:** Enables data extraction and filtering of datasets for use by investigators and principal investigators.
* **Study Build:** Facilitates creation and management of studies (protocols), sites, CRFs, users and study event definitions by principal investigators and coordinators.
* **Administration:** Allows overall system oversight, auditing, configuration, and reporting by administrators.

Some key features of OpenClinica include: 

* Organization of clinical research by study protocol and site, each with its own set of authorized users, subjects, study event definitions, and CRFs. Support for sharing resources across studies in a secure and transparent manner. 
* Dynamic generation of web-based CRFs for electronic data capture via user-defined clinical parameters and validation logic specified in portable Excel templates.
* Management of longitudinal data for complex and recurring patient visits.
* Data import/export tools for migration of clinical datasets in excel spreadsheets, local databases and legacy data formats.
* Extensive interfaces for data query and retrieval, across subjects, time, and clinical parameters, with dataset export in common statistical analysis formats. 
* Compliance with 21 CFR Part 11 and HIPAA privacy and security guidelines including use of study-specific user roles and privileges, SSL encryption, and auditing to monitor access and changes by users.
* A robust and scalable technology infrastructure developed using the Java J2EE framework interoperable with relational databases including PostgreSQL (open source) and modern Oracle, to support the needs of the clinical research enterprise. 

## II. Issues Addressed and Known Issues

This is an alpha release. To view the features and bugs being worked on for 3.1, please go to [https://www.openclinica.org/bugtracker/roadmap_page.php?project_id=84](https://www.openclinica.org/bugtracker/roadmap_page.php?project_id=84)

## III. Software Dependencies and System Requirements

Pre-requisites (versions):
* Operating system(s): Modern Linux, Windows, or macOS
* Browsers: Modern browsers (Chrome, Firefox, Safari, Edge)

Please refer to the root `README.md` for current technical requirements including JDK, Tomcat, and PostgreSQL versions.
* OpenClinica version for Upgrades only: OpenClinica 3.0.x

The source code has been removed from the distribution package to make it easier to navigate the file structure. To access the source code, please follow the instructions at [http://www.openclinica.org/dokuwiki/doku.php?id=developerwiki:start#developing_with_openclinica](http://www.openclinica.org/dokuwiki/doku.php?id=developerwiki:start#developing_with_openclinica).

**NOTE:** You must configure the smtp host correctly, or the web page may time out when trying to send emails. 

## IV. About OpenClinica

**OpenClinica: Open Source Software Platform for Clinical Trials Electronic Data Capture**
*Professional Open Source Solutions for the Clinical Research Enterprise*

OpenClinica is a free, open source clinical trial software platform for Electronic Data Capture (EDC) clinical data management in clinical research. The software is web-based and designed to support all types of clinical studies in diverse research settings. From the ground up, OpenClinica is built on leading independent standards to achieve high levels of interoperability. Its modular architecture and transparent, collaborative development model offer outstanding flexibility while supporting a robust, enterprise-quality solution.

More about OpenClinica: [http://www.OpenClinica.org](http://www.OpenClinica.org)

### Software License

OpenClinica is distributed under the GNU Lesser General Public License (GNU LGPL). For details see: [http://www.openclinica.org/license](http://www.openclinica.org/license) or LICENSE.txt distributed with this distribution.

### Developer and Contact Information

Akaza Research, based in Waltham, MA, provides clinical trials informatics solutions based on OpenClinica, the world's most widely used open source clinical trials software.

Akaza Research
460 Totten Pond Rd, Suite 200
Waltham, MA 02451
phone: 617.621.8585
fax: 617.621.0065
email: contact@akazaresearch.com

For more about Akaza’s products and services see:
[http://www.OpenClinica.com/](http://www.OpenClinica.com/)

## V. GNU LGPL License

OpenClinica is distributed under the GNU Lesser General Public License (GNU LGPL), summarized in the Creative Commons text here:

[http://creativecommons.org/licenses/LGPL/2.1/](http://creativecommons.org/licenses/LGPL/2.1/)

The GNU Lesser General Public License is a Free Software license. Like any Free Software license, it grants to you the four following freedoms:

0. The freedom to run the program for any purpose.
1. The freedom to study how the program works and adapt it to your needs.
2. The freedom to redistribute copies so you can help your neighbor.
3. The freedom to improve the program and release your improvements to the public, so that the whole community benefits.

You may exercise the freedoms specified here provided that you comply with the express conditions of this license. The LGPL is intended for software libraries, rather than executable programs.

The principal conditions are:

* You must conspicuously and appropriately publish on each copy distributed an appropriate copyright notice and disclaimer of warranty and keep intact all the notices that refer to this License and to the absence of any warranty; and give any other recipients of the Program a copy of the GNU Lesser General Public License along with the Program. Any translation of the GNU Lesser General Public License must be accompanied by the GNU Lesser General Public License.
* If you modify your copy or copies of the library or any portion of it, you may distribute the resulting library provided you do so under the GNU Lesser General Public License. However, programs that link to the library may be licensed under terms of your choice, so long as the library itself can be changed. Any translation of the GNU Lesser General Public License must be accompanied by the GNU Lesser General Public License.
* If you copy or distribute the library, you must accompany it with the complete corresponding machine-readable source code or with a written offer, valid for at least three years, to furnish the complete corresponding machine-readable source code. You need not provide source code to programs which link to the library.

Any of these conditions can be waived if you get permission from the copyright holder.
Your fair use and other rights are in no way affected by the above.

For the full GNU LGPL License text, see LICENSE.txt included in this package.
