
import React from 'react';
import { ChevronRight, Database, Users, Share2, Grid, List, Search } from 'lucide-react';

export const Header: React.FC = () => {
  return (
    <header className="h-12 border-b border-gray-200 bg-white flex items-center px-4 justify-between shrink-0">
      <div className="flex items-center gap-2 text-sm text-gray-500 overflow-hidden">
        <div className="flex items-center gap-1 hover:text-gray-800 cursor-pointer">
            <Database size={16} className="text-blue-500" />
            <span className="font-medium whitespace-nowrap">E-commerce</span>
        </div>
        <ChevronRight size={14} />
        <div className="flex items-center gap-1 hover:text-gray-800 cursor-pointer">
            <Database size={16} className="text-gray-400" />
            <span className="whitespace-nowrap">Customers</span>
        </div>
        <ChevronRight size={14} />
        <div className="flex items-center gap-1 text-gray-800 font-medium">
            <Grid size={16} className="text-blue-500" />
            <span className="whitespace-nowrap">Default View</span>
        </div>
      </div>

      <div className="flex items-center gap-2">
        <div className="bg-gray-100 rounded-lg p-0.5 flex">
            <button className="flex items-center gap-1 px-3 py-1 bg-white shadow-sm rounded-md text-xs font-medium text-blue-600">
                <Grid size={14} />
                Data
            </button>
            <button className="flex items-center gap-1 px-3 py-1 text-xs font-medium text-gray-500 hover:text-gray-700">
                <List size={14} />
                Details
            </button>
        </div>
        <button className="bg-blue-600 hover:bg-blue-700 text-white px-3 py-1.5 rounded-md flex items-center gap-2 text-sm font-medium transition-colors">
            <Share2 size={14} />
            Share
        </button>
        <button className="p-1.5 text-gray-400 hover:text-gray-600">
            <Search size={18} />
        </button>
      </div>
    </header>
  );
};
