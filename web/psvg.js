/* $Id: psvg.js,v 1.2 2020/03/12 16:57:31 horst Exp $ */

"use strict";

var ws = null;

function connect() {
  var target = 'ws://' + window.location.host + '/PiHive/piautomation';
  if ('WebSocket' in window) {
    ws = new WebSocket(target);
  } else if ('MozWebSocket' in window) {
    ws = new MozWebSocket(target);
  } else {
    alert('WebSocket is not supported by this browser.');
    return;
  }
  ws.onopen = function () {
    ws.send('syscmd graphics ' + document.documentElement.clientWidth + ' ' + document.documentElement.clientHeight); // svg for server default time back
  };
  ws.onmessage = function (event) {
    var message = event.data;
    var console = document.getElementById('console');
    var pattern = /^<.*>$/m;
    if (pattern.test(message)) {
      console.innerHTML = '';
      if (message.startsWith('<svg')) {
        // workaround https://stackoverflow.com/questions/9723422/is-there-some-innerhtml-replacement-in-svg-xml
        var svg = new DOMParser().parseFromString(message, 'application/xml');
        console.appendChild(document.importNode(svg.documentElement, true));
      } else {
        console.innerHTML = message;
      }
    }
  };
  ws.onclose = function (event) {
    document.getElementById('console').innerHTML='<p>Info: WebSocket connection closed, Code: ' + event.code + (event.reason === "" ? "" : ", Reason: " + event.reason)+ '</p>';
  };
};

function disconnect() {
  if (ws !== null) {
    ws.close();
    ws = null;
  }
};

document.addEventListener("DOMContentLoaded", function () {
  // Remove elements with "noscript" class - <noscript> is not allowed in XHTML
  var noscripts = document.getElementsByClassName("noscript");
  for (var i = 0; i < noscripts.length; i++) {
    noscripts[i].parentNode.removeChild(noscripts[i]);
  }
}, false);


